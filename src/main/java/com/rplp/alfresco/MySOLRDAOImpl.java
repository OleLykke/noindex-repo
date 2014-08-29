package com.rplp.alfresco;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.domain.node.Node;
import org.alfresco.repo.domain.qname.QNameDAO;
import org.alfresco.repo.domain.solr.AclEntity;
import org.alfresco.repo.domain.solr.NodeParametersEntity;
import org.alfresco.repo.domain.solr.SOLRDAO;
import org.alfresco.repo.domain.solr.SOLRTrackingParameters;
import org.alfresco.repo.solr.Acl;
import org.alfresco.repo.solr.AclChangeSet;
import org.alfresco.repo.solr.NodeParameters;
import org.alfresco.repo.solr.Transaction;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.ExpiringValueCache;
import org.alfresco.util.Pair;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.session.RowBounds;
import org.mybatis.spring.SqlSessionTemplate;

public class MySOLRDAOImpl implements SOLRDAO {
	private static Log logger = LogFactory.getLog(MySOLRDAOImpl.class);
	
	private static final String SELECT_CHANGESETS_SUMMARY = "alfresco.solr.select_ChangeSets_Summary";
	private static final String SELECT_ACLS_BY_CHANGESET_IDS = "alfresco.solr.select_AclsByChangeSetIds";
	private static final String SELECT_TRANSACTIONS = "alfresco.solr.select_Txns";
	private static final String SELECT_NODES = "alfresco.solr.select_Txn_Nodes";
	
	private final static long TIMEOUT_DEFAULT = 1000L*60L;
	
	private SqlSessionTemplate template;
    private QNameDAO qnameDAO;
    private NodeService nodeService;
    
    private static ExpiringValueCache<HashSet<NodeRef>> nodeCache = new ExpiringValueCache<HashSet<NodeRef>>(TIMEOUT_DEFAULT); 

    public final void setSqlSessionTemplate(SqlSessionTemplate sqlSessionTemplate) 
    {
        this.template = sqlSessionTemplate;
    }

    public void setQNameDAO(QNameDAO qnameDAO)
    {
        this.qnameDAO = qnameDAO;
    }
    
    public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}
    
    public void setTimeout(String timeout) {
    	long timeoutInMs = Long.parseLong(timeout);
    	if (timeoutInMs < TIMEOUT_DEFAULT) {
    		timeoutInMs = TIMEOUT_DEFAULT;
    	}
    	//ExpiringValueCache<Set<Node>> e = new ExpiringValueCache<HashSet<Node>>(timeoutInMs);
    	//nodeCache = new ExpiringValueCache<HashSet<Node>>(timeoutInMs);
    	//logger.info("Timeout set to " + timeout);
       }

	/**
     * Initialize
     */    
    public void init()
    {
        PropertyCheck.mandatory(this, "template", template);
        PropertyCheck.mandatory(this, "qnameDAO", qnameDAO);
    	//logger.info("*** Custom version of SOLRDAOImpl invoked");
    }
	
	@Override
	@SuppressWarnings("unchecked")
	public List<AclChangeSet> getAclChangeSets(Long minAclChangeSetId,
			Long fromCommitTime, Long maxAclChangeSetId, Long toCommitTime,
			int maxResults) {
		 
		 if (maxResults <= 0 || maxResults == Integer.MAX_VALUE)
	        {
	            throw new IllegalArgumentException("Maximum results must be a reasonable number.");
	        }

	        // We simulate an ID for the sys:deleted type
	        Pair<Long, QName> deletedTypeQNamePair = qnameDAO.getQName(ContentModel.TYPE_DELETED);
	        Long deletedTypeQNameId = deletedTypeQNamePair == null ? -1L : deletedTypeQNamePair.getFirst();

	        SOLRTrackingParameters params = new SOLRTrackingParameters(deletedTypeQNameId);
	        params.setFromIdInclusive(minAclChangeSetId);
	        params.setFromCommitTimeInclusive(fromCommitTime);
	        params.setToIdExclusive(maxAclChangeSetId);
	        params.setToCommitTimeExclusive(toCommitTime);

	        return (List<AclChangeSet>) template.selectList(SELECT_CHANGESETS_SUMMARY, params, new RowBounds(0, maxResults));
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<Acl> getAcls(List<Long> aclChangeSetIds, Long minAclId,
			int maxResults) {
		if (aclChangeSetIds == null || aclChangeSetIds.size() == 0)
        {
            throw new IllegalArgumentException("'aclChangeSetIds' must contain IDs.");
        }
        if (aclChangeSetIds.size() > 512)
        {
            throw new IllegalArgumentException("'aclChangeSetIds' cannot have more than 512 entries.");
        }
        
        // We simulate an ID for the sys:deleted type
        Pair<Long, QName> deletedTypeQNamePair = qnameDAO.getQName(ContentModel.TYPE_DELETED);
        Long deletedTypeQNameId = deletedTypeQNamePair == null ? -1L : deletedTypeQNamePair.getFirst();

        SOLRTrackingParameters params = new SOLRTrackingParameters(deletedTypeQNameId);
        params.setIds(aclChangeSetIds);
        params.setFromIdInclusive(minAclId);

        List<Acl> source;
        if (maxResults <= 0 || maxResults == Integer.MAX_VALUE)
        {
            source = (List<Acl>) template.selectList(SELECT_ACLS_BY_CHANGESET_IDS, params);
        }
        else
        {
            source = (List<Acl>) template.selectList(SELECT_ACLS_BY_CHANGESET_IDS, params, new RowBounds(0, maxResults));
        }
        // Add any unlinked shared ACLs from defining nodes to index them now
        TreeSet<Acl> sorted = new TreeSet<Acl>(source);
        HashSet<Long> found = new HashSet<Long>();
        for(Acl acl : source)
        {
            found.add(acl.getId());
        }
        
        for(Acl acl : source)
        {
            if(acl.getInheritedId() != null)
            {
                if(!found.contains(acl.getInheritedId()))
                {
                    AclEntity shared = new AclEntity();
                    shared.setId(acl.getInheritedId());
                    shared.setAclChangeSetId(acl.getAclChangeSetId());
                    shared.setInheritedId(acl.getInheritedId());
                    sorted.add(shared);
                }
            }
        }

        ArrayList<Acl> answer = new ArrayList<Acl>();
        answer.addAll(sorted);
        return answer;
    }

	@Override
	@SuppressWarnings("unchecked")
	public List<Transaction> getTransactions(Long minTxnId,
			Long fromCommitTime, Long maxTxnId, Long toCommitTime,
			int maxResults) {
		
		 if (maxResults <= 0 || maxResults == Integer.MAX_VALUE)
	        {
	            throw new IllegalArgumentException("Maximum results must be a reasonable number.");
	        }

	        // We simulate an ID for the sys:deleted type
	        Pair<Long, QName> deletedTypeQNamePair = qnameDAO.getQName(ContentModel.TYPE_DELETED);
	        Long deletedTypeQNameId = deletedTypeQNamePair == null ? -1L : deletedTypeQNamePair.getFirst();

	        SOLRTrackingParameters params = new SOLRTrackingParameters(deletedTypeQNameId);
		    params.setFromIdInclusive(minTxnId);
		    params.setFromCommitTimeInclusive(fromCommitTime);
		    params.setToIdExclusive(maxTxnId);
	        params.setToCommitTimeExclusive(toCommitTime);

	        return (List<Transaction>) template.selectList(SELECT_TRANSACTIONS, params, new RowBounds(0, maxResults));
	}

	/**
     * {@inheritDoc}
     */
	@Override
	@SuppressWarnings("unchecked")
	public List<Node> getNodes(NodeParameters nodeParameters)
	{
    	NodeParametersEntity params = new NodeParametersEntity(nodeParameters, qnameDAO);
    	
    	List<Node> result;

	    if(nodeParameters.getMaxResults() != 0 && nodeParameters.getMaxResults() != Integer.MAX_VALUE)
	    {
	        result = (List<Node>) template.selectList(
	                SELECT_NODES, params,
	                new RowBounds(0, nodeParameters.getMaxResults()));
	    }
	    else
	    {
	        result = (List<Node>) template.selectList(SELECT_NODES, params);
	    }
	    
	    /*
	     * Pseudokode
	     * hvis der findes en liste af aspekter, som skal ekskluderes fra indexering
	     * filtrer resultatet...
	     * for hver noderef i resultatet
	     *    check om det har et aspekt tilknyttet, som også optræde i listen af aspekter, som skal eksluderes
	     *    hvis ja
	     *      fjern noderef fra resultatet
	     *    
	     * TODO: cache de nodes, som skal eksluderes... det er de samme igen og igen ... brug expiringCache
	     */
	    Set<QName> excludeAspects = params.getExcludeAspects();
	    
		if (excludeAspects != null && excludeAspects.size() > 0) {
			List<Node> filteredResult = new ArrayList<Node>(result.size());
			for (Node node : result) {
				NodeRef nodeRef = node.getNodeRef();
				if (nodeService.exists(nodeRef)) {
					if (isNodeCached(node)) {
						/*
						 * if (logger.isDebugEnabled()) { logger.debug(
						 * "It was previously decided to ignore Node with name: "
						 * + nodeService.getProperty( node.getNodeRef(),
						 * ContentModel.PROP_NAME)); }
						 */
						logger.debug("indexer ikke "
								+ nodeService.getProperty(nodeRef,
										ContentModel.PROP_NAME));
					} else {
						for (QName excludeAspect : excludeAspects) {
							if (hasThisOrAncestorExcludeAspect(nodeRef,
									excludeAspect)) {
								addNodeToCache(node);
								logger.debug("indexer ikke "
										+ nodeService.getProperty(
												nodeRef,
												ContentModel.PROP_NAME));
							} else {
								filteredResult.add(node);
								logger.debug("indexer     "
										+ nodeService.getProperty(
												nodeRef,
												ContentModel.PROP_NAME));
							}
						}
					}
				}
			}
		    result = filteredResult;
		}

		return result;
	}	
	
	/**
	 * Checks whether this nodeRef one of its ancestors has an aspect indicating this nodeRef should not be indexed
	 * @param nodeRef the nodeRef we want to check
	 * @param excludeAspect the aspects to check for
	 * @return true if this nodeRef or an ancestor has the aspect otherwise false
	 */
	private boolean hasThisOrAncestorExcludeAspect(NodeRef nodeRef, QName excludeAspect) {
		boolean hasAspect = false;
		while (nodeRef != null) {
			if (nodeService.hasAspect(nodeRef, excludeAspect)) {
				return true;
			}
			nodeRef = nodeService.getPrimaryParent(nodeRef).getParentRef();
		}
	    return hasAspect;
	}
	
	
	private boolean isNodeCached(Node node) {
		boolean found = false;
		if (nodeCache == null) {
			logger.error("excludeAspectsCache is null");
			found = false;
		} else {
			HashSet<NodeRef> set = nodeCache.get();
			if (set == null) {
				found = false;
			} else {
				found = set.contains(node.getNodeRef());
			}
		}
		return found;
	}

	private void addNodeToCache(Node node) {
		HashSet<NodeRef> cachedNodes = nodeCache.get();
		if (cachedNodes == null) {
			cachedNodes = new HashSet<NodeRef>();
		}
		cachedNodes.add(node.getNodeRef());
		nodeCache.put(cachedNodes);
	}

}
