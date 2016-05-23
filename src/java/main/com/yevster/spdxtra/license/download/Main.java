package com.yevster.spdxtra.license.download;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.jena.iri.IRIFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotWriter;

import com.yevster.spdxtra.LicenseList.ListedLicense;
import com.yevster.spdxtra.Read;
import com.yevster.spdxtra.SpdxProperties;
import com.yevster.spdxtra.SpdxUris;
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

			licenseDataSet.getDefaultModel().getGraph().getPrefixMapping().setNsPrefix("spdx", SpdxUris.SPDX_TERMS);
			// Read the RDFa into an in-memory RDF model.
			new HTMLRDFaReader().read(fetchedLicenseListModel, response.getEntity().getContent(),
					"http://www.w3.org/1999/xhtml:html");
			String licenseListVersion = fetchedLicenseListModel
					.listObjectsOfProperty(LicenseListProperties.LICENSE_LIST_VERSION).next().asLiteral().getString();
			System.out.println("License list version "+licenseListVersion);
			// Create the license list, set the version.
			Resource licenseListResource = licenseDataSet.getDefaultModel().createResource(LICENSE_LIST_URL);
			licenseListResource.addLiteral(LicenseListProperties.LICENSE_LIST_VERSION, licenseListVersion);

			// For each retrieved license ID, fetch that license and add to the
			// saved license list. SPDXtra already has code to do this.
			MiscUtils.toLinearStream(fetchedLicenseListModel.listObjectsOfProperty(LicenseListProperties.LICENSE_ID))
					.map(RDFNode::asLiteral).map(Literal::getString)
					// Got the license ID, get the license:
					.sorted(String::compareToIgnoreCase)
					.peek(licenseId -> System.out.println("Downloading license " + licenseId))
					.map(Main::getListedLicenseById)
					// Got the resource. Now, let's extract the parts we care
					// about by building up a license.
					// And write it as an RDF node as we would to an SPDX
					// document. This will preserve only the important
					// properties, and discard the fluff.
					.sequential()
						.forEach(license -> {
						licenseListResource.addProperty(SpdxProperties.LICENSE_LIST_LICENSE,
							license.getRdfNodeFull(licenseDataSet.getDefaultModel()));
						// Wait a bit to keep from DOSing the server.
						try {
							Thread.sleep(1000);
						} catch (InterruptedException ie) {
						}
					});
			;

			try (OutputStream out = Files.newOutputStream(targetFile)) {
				RDFDataMgr.write(out, licenseDataSet, Lang.RDFTHRIFT);
			}


		} catch (IOException e) {
			throw new LicenseRetrievalException("Error accessing " + accessUrl, e);
		}

	}

	private static final class PopulatingListedLicense extends ListedLicense {
		public PopulatingListedLicense(Resource r) {
			super(r);
		}
		
	}

	public static class LicenseRetrievalException extends RuntimeException {

		private static final long serialVersionUID = -638212004386585080L;

		public LicenseRetrievalException(String s, Throwable cause) {
			super(s, cause);
		}

		public LicenseRetrievalException(String s) {
			super(s);
		}
	}

	private static PopulatingListedLicense getListedLicenseById(String id) {
		// Verify arguments
		if (StringUtils.isBlank(id)) {
			throw new IllegalArgumentException("Cannot get listed license with null or empty id");
		} // For security
		if (StringUtils.containsAny(id, '/', ':')) {
			throw new IllegalArgumentException("Illegal characters in id " + id);
		}

		String licenseUri = LICENSE_LIST_URL + id;

		try {
			Model model = ModelFactory.createDefaultModel();
			HttpClient httpClient = new DefaultHttpClient();
			HttpUriRequest request = new HttpGet(licenseUri);
			HttpResponse response = httpClient.execute(request);

			if (response.getStatusLine().getStatusCode() != 200) {
				throw new LicenseRetrievalException("Error accessing " + licenseUri + ". Status returned: "
						+ response.getStatusLine().getStatusCode() + ". Reason:"
						+ response.getStatusLine().getReasonPhrase());
			}

			new HTMLRDFaReader().read(model, response.getEntity().getContent(), "http://www.w3.org/1999/xhtml:html");

			Resource foundLicense = model.listSubjects().next();
			return new PopulatingListedLicense(foundLicense);
		} catch (IOException e) {
			throw new LicenseRetrievalException("Error accessing " + licenseUri, e);
		}
	}
}
