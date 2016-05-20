package com.yevster.spdxtra.license.download;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.impl.PropertyImpl;

import com.yevster.spdxtra.SpdxUris;

public class LicenseListProperties {
	
	public static final Property LICENSE_ID = new PropertyImpl(SpdxUris.SPDX_TERMS, "licenseId");
	public static final Property LICENSE_LIST_VERSION = new PropertyImpl(SpdxUris.SPDX_TERMS, "licenseListVersion");

}
