package com.yevster.spdxtra.license.download;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.writer.RDFJSONWriter;

import com.yevster.spdxtra.LicenseList;
import com.yevster.spdxtra.LicenseList.LicenseRetrievalException;
import com.yevster.spdxtra.license.download.LicenseListProperties;
import com.yevster.spdxtra.util.MiscUtils;

import net.rootdev.javardfa.jena.RDFaReader.HTMLRDFaReader;

/**
 * Downloads the SPDX license list into a single file.
 */
public class Main {
	private static final String LICENSE_LIST_URL = "http://spdx.org/licenses/";

	public static void main(String[] args) {
		String accessUrl = LICENSE_LIST_URL;
		if (args.length < 1) {
			System.err.println("Destination file required");
			throw new IllegalArgumentException("Insufficient Arguments");
		}
		Path targetFile = Paths.get(args[0]);
		if (Files.exists(targetFile)) {
			throw new IllegalArgumentException("File already exists: " + targetFile.toString());
		}

		Dataset licenseDataSet = DatasetFactory.create();
		try {
			Model fetchedLicenseListModel = ModelFactory.createDefaultModel();

			HttpClient httpClient = new DefaultHttpClient();
			HttpUriRequest request = new HttpGet(accessUrl);
			HttpResponse response = httpClient.execute(request);

			if (response == null || response.getStatusLine().getStatusCode() != 200) {
				throw new LicenseRetrievalException(
						"Error accessing " + LICENSE_LIST_URL + ". Status: " + response.getStatusLine().toString());
			}
			// Read the RDFa into an in-memory RDF model.
			new HTMLRDFaReader().read(fetchedLicenseListModel, response.getEntity().getContent(),
					"http://www.w3.org/1999/xhtml:html");
			String licenseListVersion = fetchedLicenseListModel
					.listObjectsOfProperty(LicenseListProperties.LICENSE_LIST_VERSION).next().asLiteral().getString();

			// Create the license list, set the version.
			Resource licenseListResource = licenseDataSet.getDefaultModel().createResource(LICENSE_LIST_URL);
			licenseListResource.addProperty(LicenseListProperties.LICENSE_LIST_VERSION, licenseListVersion);

			// For each retrieved license ID, fetch that license and add to the
			// saved license list.
			// SPDXtra already has code to do this.
			MiscUtils.toLinearStream(fetchedLicenseListModel.listObjectsOfProperty(LicenseListProperties.LICENSE_ID))
					.map(RDFNode::asLiteral).map(Literal::getString)
					// Got the license ID, get the license:
					.sorted(String::compareToIgnoreCase).peek(licenseId -> System.out.println("Downloading license " + licenseId))
					.map(LicenseList.INSTANCE::getListedLicenseById).map(Optional::get) 
					.forEach(license -> {
						// Avoid DOSing the server
						try {
							Thread.sleep(1000);
						} catch (Exception e) {
						}
						licenseListResource.addProperty(LicenseListProperties.LICENSE,
								license.getRdfNode(licenseDataSet.getDefaultModel()));
					});
			try (OutputStream out = Files.newOutputStream(targetFile)) {
				RDFDataMgr.write(out, licenseDataSet, Lang.RDFTHRIFT);
			}

		} catch (IOException e) {
			throw new LicenseRetrievalException("Error accessing " + accessUrl, e);
		}

	}
}
