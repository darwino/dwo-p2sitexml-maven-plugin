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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.ibm.commons.util.StringUtil;
import com.ibm.commons.util.io.StreamUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * Goal which generates a site.xml file from a features directory.
 */
@Mojo(name="generate-site-xml", defaultPhase=LifecyclePhase.PREPARE_PACKAGE)
public class GenerateSiteXmlMojo extends AbstractMojo {
	private static final Pattern FEATURE_FILENAME_PATTERN = Pattern.compile("^([^_]+)_(.*)\\.jar$"); //$NON-NLS-1$
	
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
		File f = p2Directory;
		System.out.println("Looking at " + f);

		if (!f.exists() || !f.isDirectory()) {
			throw new MojoExecutionException("Repository directory does not exist.");
		} else {
			File features = new File(f, "features"); //$NON-NLS-1$
			if(!features.exists() || !features.isDirectory()) {
				throw new MojoExecutionException("Unable to find features directory: " + features.getAbsolutePath());
			}
			
			try {
				Document doc = DOMUtil.createDocument();
				Element root = DOMUtil.createElement(doc, "site"); //$NON-NLS-1$
				
				// Create the category entry if applicable
				String category = this.category;
				Set<String> createdCategories = new HashSet<>();
				if(StringUtil.isNotEmpty(category)) {
					Element categoryDef = DOMUtil.createElement(doc, root, "category-def"); //$NON-NLS-1$
					categoryDef.setAttribute("name", category); //$NON-NLS-1$
					categoryDef.setAttribute("label", category); //$NON-NLS-1$
				}

				Document content = null;
				File contentFile = new File(f, "content.xml");
				if(contentFile.exists()) {
					try(InputStream is = new FileInputStream(contentFile)) {
						content = DOMUtil.createDocument(is);
					}
				} else {
					// Check for content.jar
					contentFile = new File(f, "content.jar");
					if(contentFile.exists()) {
						try(InputStream is = new FileInputStream(contentFile)) {
							try(ZipInputStream zis = new ZipInputStream(is)) {
								zis.getNextEntry();
								content = DOMUtil.createDocument(zis);
							}
						}
					}
				}
				if(content == null) {
					content = DOMUtil.createDocument();
				}

				
				String[] featureFiles = features.list(new FilenameFilter() {
					@Override public boolean accept(File parent, String fileName) {
						return StringUtil.toString(fileName).toLowerCase().endsWith(".jar"); //$NON-NLS-1$
					}
				});
				
				for(String featureFilename : featureFiles) {
					Matcher matcher = FEATURE_FILENAME_PATTERN.matcher(featureFilename);
					if(!matcher.matches()) {
						throw new IllegalStateException("Could not match filename pattern to " + featureFilename);
					}
					getLog().debug("Filename matcher groups: " + matcher.groupCount());
					String featureName = matcher.group(1);
					String version = matcher.group(2);
					
					Element featureElement = DOMUtil.createElement(doc, root, "feature"); //$NON-NLS-1$
					String url = "features/" + featureFilename; //$NON-NLS-1$
					featureElement.setAttribute("url", url); //$NON-NLS-1$
					featureElement.setAttribute("id", featureName); //$NON-NLS-1$
					featureElement.setAttribute("version", version); //$NON-NLS-1$
					
					if(StringUtil.isNotEmpty(category)) {
						Element categoryElement = DOMUtil.createElement(doc, featureElement, "category"); //$NON-NLS-1$
						categoryElement.setAttribute("name", category); //$NON-NLS-1$
					} else {
						// See if it's referenced in any content.xml features
						Object[] matches = DOMUtil.nodes(content, StringUtil.format("/repository/units/unit/requires/required[@name='{0}']", featureName + ".feature.group"));
						if(matches.length > 0) {
							for(Object match : matches) {
								if(match instanceof Element) {
									Element matchEl = (Element)match;
									Node unit = matchEl.getParentNode().getParentNode();
									// Make sure the parent is a category
									boolean isCategory = DOMUtil.node(unit, "properties/property[@name='org.eclipse.equinox.p2.type.category']") != null;
									if(isCategory) {
										String categoryName = DOMUtil.value(unit, "properties/property[@name='org.eclipse.equinox.p2.name']/@value");
										Element categoryElement = DOMUtil.createElement(doc, featureElement, "category"); //$NON-NLS-1$
										categoryElement.setAttribute("name", categoryName); //$NON-NLS-1$

										if(!createdCategories.contains(categoryName)) {
											Element categoryDef = DOMUtil.createElement(doc, root, "category-def"); //$NON-NLS-1$
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
				
				String xml = DOMUtil.getXMLString(doc, false, true);
				File output = new File(f, "site.xml"); //$NON-NLS-1$
				FileWriter w = null;
				try {
					w = new FileWriter(output);
					w.write(xml);
				} catch (IOException e) {
					throw new MojoExecutionException("Error writing site.xml file", e);
				} finally {
					StreamUtil.close(w);
				}
				
				getLog().info(StringUtil.format("Wrote site.xml contents to {0}", output.getAbsolutePath()));
			} catch(XMLException | IOException e) {
				throw new MojoExecutionException("Exception while building site.xml document", e);
			}
		}
	}
}
