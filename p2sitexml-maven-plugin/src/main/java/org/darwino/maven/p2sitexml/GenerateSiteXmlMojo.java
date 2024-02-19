/**
 * Copyright (c) 2017 Darwino, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.darwino.maven.p2sitexml;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openntf.nsfodp.commons.xml.NSFODPDomUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.ibm.commons.util.StringUtil;

/**
 * Goal which generates a site.xml file from a features directory.
 */
@Mojo(name="generate-site-xml", defaultPhase=LifecyclePhase.PREPARE_PACKAGE)
public class GenerateSiteXmlMojo extends AbstractMojo {
	/**
	 * Location of the p2 repository.
	 */
	@Parameter(defaultValue="${project.build.directory}/repository", property="p2Directory", required=false)
	private File p2Directory;
	/**
	 * The category name to use for the features in the site.
	 */
	@Parameter(defaultValue="", property="category", required=false)
	private String category;

	@Override
	public void execute() throws MojoExecutionException {
		Path f = p2Directory.toPath();
		Log log = getLog();
		if(log.isInfoEnabled()) {
			log.info(MessageFormat.format("Looking at {0}", f));
		}

		if (!Files.isDirectory(f)) {
			throw new MojoExecutionException("Repository directory does not exist.");
		} else {
			Path features = f.resolve("features"); //$NON-NLS-1$
			if(!Files.isDirectory(features)) {
				throw new MojoExecutionException("Unable to find features directory: " + features);
			}
			
			try {
				Document doc = NSFODPDomUtil.createDocument();
				Element root = NSFODPDomUtil.createElement(doc, "site"); //$NON-NLS-1$
				
				// Create the category entry if applicable
				String category = this.category;
				Set<String> createdCategories = new HashSet<>();
				if(StringUtil.isNotEmpty(category)) {
					Element categoryDef = NSFODPDomUtil.createElement(root, "category-def"); //$NON-NLS-1$
					categoryDef.setAttribute("name", category); //$NON-NLS-1$
					categoryDef.setAttribute("label", category); //$NON-NLS-1$
				}

				Document content = null;
				Path contentFile = f.resolve("content.xml"); //$NON-NLS-1$
				if(Files.isRegularFile(contentFile)) {
					try(InputStream is = Files.newInputStream(contentFile)) {
						content = NSFODPDomUtil.createDocument(is);
					}
				} else {
					// Check for content.jar
					contentFile = f.resolve("content.jar"); //$NON-NLS-1$
					if(Files.isRegularFile(contentFile)) {
						try(InputStream is = Files.newInputStream(contentFile)) {
							try(ZipInputStream zis = new ZipInputStream(is)) {
								zis.getNextEntry();
								content = NSFODPDomUtil.createDocument(zis);
							}
						}
					}
				}
				if(content == null) {
					content = NSFODPDomUtil.createDocument();
				}

				
				Path[] featureFiles = Files.list(features)
					.filter(path -> path.getFileName().toString().endsWith(".jar")) //$NON-NLS-1$
					.toArray(Path[]::new);
				
				for(Path featureFile : featureFiles) {
					try(
						InputStream is = Files.newInputStream(featureFile);
						ZipInputStream zis = new ZipInputStream(is, StandardCharsets.UTF_8)
					) {
						ZipEntry siteEntry = zis.getNextEntry();
						while(siteEntry != null) {
							if("feature.xml".equals(siteEntry.getName())) { //$NON-NLS-1$
								Document featureXml = NSFODPDomUtil.createDocument(zis);
								Element featureRootElement = featureXml.getDocumentElement();

								String featureName = featureRootElement.getAttribute("id"); //$NON-NLS-1$
								String version = featureRootElement.getAttribute("version"); //$NON-NLS-1$
								
								Element featureElement = NSFODPDomUtil.createElement(root, "feature"); //$NON-NLS-1$
								String url = f.relativize(featureFile).toString();
								featureElement.setAttribute("url", url); //$NON-NLS-1$
								featureElement.setAttribute("id", featureName); //$NON-NLS-1$
								featureElement.setAttribute("version", version); //$NON-NLS-1$
								
								if(StringUtil.isNotEmpty(category)) {
									Element categoryElement = NSFODPDomUtil.createElement(featureElement, "category"); //$NON-NLS-1$
									categoryElement.setAttribute("name", category); //$NON-NLS-1$
								} else {
									findCategory(content, root, featureElement, featureName, createdCategories);
								}
								
								break;
							}
							
							siteEntry = zis.getNextEntry();
						}
					}
				}
				
				String xml = NSFODPDomUtil.getXmlString(doc, null);
				Path output = f.resolve("site.xml"); //$NON-NLS-1$
				try(BufferedWriter w = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					w.write(xml);
				} catch (IOException e) {
					throw new MojoExecutionException("Error writing site.xml file", e);
				}
				
				if(log.isInfoEnabled()) {
					log.info(StringUtil.format("Wrote site.xml contents to {0}", output));
				}
			} catch(IOException e) {
				throw new MojoExecutionException("Exception while building site.xml document", e);
			}
		}
	}
	
	private void findCategory(Document content, Element siteXml, Element featureElement, String featureName, Collection<String> createdCategories) {
		// See if it's referenced in any content.xml features
		List<Node> matches = NSFODPDomUtil.nodes(content, StringUtil.format("/repository/units/unit/requires/required[@name='{0}']", featureName + ".feature.group")); //$NON-NLS-1$
		if(!matches.isEmpty()) {
			for(Object match : matches) {
				if(match instanceof Element) {
					Element matchEl = (Element)match;
					Node unit = matchEl.getParentNode().getParentNode();
					// Make sure the parent is a category
					Element categoryNode = (Element)NSFODPDomUtil.node(unit, "properties/property[@name='org.eclipse.equinox.p2.type.category']").orElse(null); //$NON-NLS-1$
					boolean isCategory = categoryNode != null && "true".equals(categoryNode.getAttribute("value")); //$NON-NLS-1$ //$NON-NLS-2$
					if(isCategory) {
						String categoryName = NSFODPDomUtil.node(unit, "properties/property[@name='org.eclipse.equinox.p2.name']/@value").get().getNodeValue(); //$NON-NLS-1$
						Element categoryElement = NSFODPDomUtil.createElement(featureElement, "category"); //$NON-NLS-1$
						categoryElement.setAttribute("name", categoryName); //$NON-NLS-1$

						if(!createdCategories.contains(categoryName)) {
							Element categoryDef = NSFODPDomUtil.createElement(siteXml, "category-def"); //$NON-NLS-1$
							categoryDef.setAttribute("name", categoryName); //$NON-NLS-1$
							categoryDef.setAttribute("label", categoryName); //$NON-NLS-1$
							createdCategories.add(categoryName);
						}
						break;
					}
				}
			}
		}
	}
}
