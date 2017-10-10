# dwo-p2sitexml-maven-plugin

A Maven plugin to generate a site.xml file from an OSGi p2 repository. This is intended as a quick way to improve use in older Eclipse/OSGi implementations, such as IBM Domino, with projects built without Tycho.

It contains one goal, `generate-site-xml`, which binds by default to the `prepare-package` phase.

This is currently a very simplistic implementation: rather than reading through the p2 metadata, it merely enumerates the features from the `features` directory and creates the `site.xml` from that. It contains two properties:

### `p2Directory`

The `p2Directory` property defaults to `${project.build.directory}/repository` and allows the user to specify the location of the built p2 repository.

### `category`

`category` is an optional property that allows for the specification of a category ID for all of the features in the site.

## Sample Usage

	<plugin>
		<groupId>org.darwino</groupId>
		<artifactId>p2sitexml-maven-plugin</artifactId>
		<version>1.0.0-SNAPSHOT</version>
		<executions>
			<execution>
				<goals>
					<goal>generate-site-xml</goal>
				</goals>
				<configuration>
					<category>Darwino Client</category>
				</configuration>
			</execution>
		</executions>
	</plugin>

## License

This project is a <a href="http://darwino.org">darwino.org</a> project and is licensed under the Apache License 2.0.