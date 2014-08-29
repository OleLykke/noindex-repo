package com.rplp.alfresco;

import org.alfresco.service.namespace.QName;

public interface NoindexModel {
	static final String NOINDEX_MODEL_URI = "http://www.alfresco.com/model/noindex/1.0";
	static final QName ASPECT_NOINDEX = QName.createQName(NOINDEX_MODEL_URI, "applied");
}
