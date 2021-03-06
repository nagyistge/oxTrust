/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.gluu.oxtrust.ldap.service.*;
import org.gluu.oxtrust.model.GluuCustomAttribute;
import org.gluu.oxtrust.model.GluuEntityType;
import org.gluu.oxtrust.model.GluuMetadataSourceType;
import org.gluu.oxtrust.model.GluuSAMLTrustRelationship;
import org.gluu.oxtrust.model.OxAuthClient;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.saml.metadata.SAMLMetadataParser;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Out;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.security.Restrict;
import org.jboss.seam.core.ResourceLoader;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.international.StatusMessage.Severity;
import org.jboss.seam.log.Log;
import org.jboss.seam.security.Identity;
import org.xdi.config.oxtrust.ApplicationConfiguration;
import org.xdi.ldap.model.GluuStatus;
import org.xdi.model.GluuAttribute;
import org.xdi.model.GluuUserRole;
import org.xdi.model.SchemaEntry;
import org.xdi.service.SchemaService;
import org.xdi.util.StringHelper;
import org.xdi.util.io.FileUploadWrapper;
import org.xdi.util.io.ResponseHelper;

import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;

/**
 * Action class for updating and adding the trust relationships
 * 
 * @author Pankaj Narang
 * @author Yuriy Movchan Date: 11.04.2010
 */
@Scope(ScopeType.CONVERSATION)
@Name("updateTrustRelationshipAction")
@Restrict("#{identity.loggedIn}")
public class UpdateTrustRelationshipAction implements Serializable {

	private static final long serialVersionUID = -1032167044333943680L;

	@Logger
	private Log log;

	@In(value = "#{oxTrustConfiguration.applicationConfiguration}")
	private ApplicationConfiguration applicationConfiguration;

	static final Class<?>[] NO_PARAM_SIGNATURE = new Class[0];

	private String inum;
	private boolean update;

	private GluuSAMLTrustRelationship trustRelationship;

	@In
	protected AttributeService attributeService;
	
	@In
	private MetadataValidationTimer metadataValidationTimer;

	@In
	private TrustService trustService;
	
	@In
	private ClientService clientService;

	@In
	private Identity identity;
	
	@In
	private TemplateService templateService;

	@In
	private SvnSyncTimer svnSyncTimer;

	@In
	private Shibboleth3ConfService shibboleth3ConfService;
	
	@In
	private FacesMessages facesMessages;

	@In(value = "#{facesContext}")
	private FacesContext facesContext;

	@In(create = true)
	@Out(scope = ScopeType.CONVERSATION)
	private TrustContactsAction trustContactsAction;

	@In(create = true)
	@Out(scope = ScopeType.CONVERSATION)
	private MetadataFiltersAction metadataFiltersAction;

	@In(create = true)
	@Out(scope = ScopeType.CONVERSATION)
	private RelyingPartyAction relyingPartyAction;

	@In(create = true)
	@Out(scope = ScopeType.CONVERSATION)
	private CustomAttributeAction customAttributeAction;

	@In(create = true)
	@Out(scope = ScopeType.CONVERSATION)
	private FederationDeconstructionAction federationDeconstructionAction;

	private FileUploadWrapper fileWrapper = new FileUploadWrapper();

	private FileUploadWrapper certWrapper = new FileUploadWrapper();

	private String selectedTR;

	private List<GluuSAMLTrustRelationship> federatedSites;

	private List<String> availableEntities;
	private List<String> filteredEntities;

	private String filterString;
	private String metadata;
	public String getMetadata() {
		return metadata;
	}

	public void setMetadata(String metadata) {
		this.metadata = metadata;
	}

	private List<String> availableEntitiesFiltered;
	//private GluuEntityType entityType;	

	@In
	 private ResourceLoader resourceLoader;
	
	public List <GluuMetadataSourceType> getMetadataSourceTypesList() {
		List<GluuMetadataSourceType> metadataSourceTypesList = (Arrays.asList(GluuMetadataSourceType.values()));
		if (GluuEntityType.FederationAggregate.equals(trustRelationship.getEntityType())) {
			List<GluuMetadataSourceType> GluuMetadataSourceTypeSubList = new ArrayList<GluuMetadataSourceType>();
			for (GluuMetadataSourceType enumType : GluuMetadataSourceType.values()) {
				if (!GluuMetadataSourceType.GENERATE.equals(enumType) && !GluuMetadataSourceType.FEDERATION.equals(enumType)) {
					GluuMetadataSourceTypeSubList.add(enumType);
				}
			}
			return GluuMetadataSourceTypeSubList;
		} else {
			return metadataSourceTypesList;
		}
	}

	@Restrict("#{s:hasPermission('trust', 'access')}")
	public String add() {
		if (this.trustRelationship != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = false;
		this.trustRelationship = new GluuSAMLTrustRelationship();
		this.trustRelationship.setMaxRefreshDelay("PT8H");
		this.fileWrapper = new FileUploadWrapper();
		this.trustRelationship.setOwner(OrganizationService.instance().getOrganization().getDn());

		boolean initActionsResult = initActions();
		if (!initActionsResult) {
			return OxTrustConstants.RESULT_FAILURE;
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	@Restrict("#{s:hasPermission('trust', 'access')}")
	public String update() {
		if (this.trustRelationship != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = true;
		try {
			this.trustRelationship = trustService.getRelationshipByInum(inum);
		} catch (LdapMappingException ex) {
			log.error("Failed to find trust relationship {0}", ex, inum);
		}

		if (this.trustRelationship == null) {
			return OxTrustConstants.RESULT_FAILURE;
		}

		this.fileWrapper = new FileUploadWrapper();
		this.fileWrapper.setFileName(this.trustRelationship.getSpMetaDataFN());

		boolean initActionsResult = initActions();
		if (!initActionsResult) {
			return OxTrustConstants.RESULT_FAILURE;
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	@Restrict("#{s:hasPermission('trust', 'access')}")
	public void cancel() {
	}

	@Restrict("#{s:hasPermission('trust', 'access')}")
	public String save() {
		synchronized (svnSyncTimer) {
			if (StringHelper.isEmpty(this.trustRelationship.getInum())) {
				this.inum = trustService.generateInumForNewTrustRelationship();
				this.trustRelationship.setInum(this.inum);
			} else {
				this.inum = this.trustRelationship.getInum();
				update=true;
			}

			boolean updateShib2Configuration = applicationConfiguration.isConfigGeneration();
			switch (trustRelationship.getSpMetaDataSourceType()) {
			case GENERATE:
				String certificate = getCertForGeneratedSP();
				GluuStatus status = StringHelper.isNotEmpty(certificate) ? GluuStatus.ACTIVE : GluuStatus.INACTIVE;
				this.trustRelationship.setStatus(status);
				if (generateSpMetaDataFile(certificate)) {
					setEntityId();
				} else {
					log.error("Failed to generate SP meta-data file");
					return OxTrustConstants.RESULT_FAILURE;
				}

				break;
			case FILE:
				if (saveSpMetaDataFileSourceTypeFile()) {
					update = true;
					updateSpMetaDataCert(certWrapper);
//					setEntityId();
					if(!update){
						this.trustRelationship.setStatus(GluuStatus.ACTIVE);
					}
				} else {
					log.error("Failed to save SP meta-data file {0}", fileWrapper);
					return OxTrustConstants.RESULT_FAILURE;
				}

				break;
			case URI:
				try {
					//if (saveSpMetaDataFileSourceTypeURI()) {
//						setEntityId();
					boolean result = shibboleth3ConfService.existsResourceUri(trustRelationship.getSpMetaDataURL());
					if(result){
						metadataValidationTimer.newThreadSaveSpMetaDataFileSourceTypeURI(this);
					}else{
						log.info("There is no resource found Uri : {0}", trustRelationship.getSpMetaDataURL());
					}
					if(!update){
						this.trustRelationship.setStatus(GluuStatus.ACTIVE);
					}
					/*} else {
						log.error("Failed to save SP meta-data file {0}", fileWrapper);
						return OxTrustConstants.RESULT_FAILURE;
					}*/
				} catch (Exception e) {
					return "unable_download_metadata";
				}
				break;
			case FEDERATION:
				if(!update){
					this.trustRelationship.setStatus(GluuStatus.ACTIVE);
				}
				if (this.trustRelationship.getEntityId() == null) {
					return "invalid_entity_id";
				}

				break;
			default:

				break;
			}

			trustService.updateReleasedAttributes(this.trustRelationship);
			boolean federation = trustService.isFederation(this.trustRelationship);
			this.trustRelationship.setFederation(federation);

			trustContactsAction.saveContacts();

			if (update) {
				try {
					saveTR(update);
				} catch (LdapMappingException ex) {
					log.error("Failed to update trust relationship {0}", ex, inum);
					return OxTrustConstants.RESULT_FAILURE;
				}
			} else {
				String dn = trustService.getDnForTrustRelationShip(this.inum);
				// Save trustRelationship
				this.trustRelationship.setDn(dn);
				try {
					saveTR(update);
				} catch (LdapMappingException ex) {
					log.error("Failed to add new trust relationship {0}", ex, this.trustRelationship.getInum());
					return OxTrustConstants.RESULT_FAILURE;
				}

				this.update = true;
			}

			if (updateShib2Configuration) {
				List<GluuSAMLTrustRelationship> trustRelationships = trustService.getAllActiveTrustRelationships();
				updateShibboleth3Configuration(trustRelationships);
			}
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private boolean initActions() {
		initAttributes(this.trustRelationship);

		String resultInitContacts = trustContactsAction.initContacts(this.trustRelationship);
		if (!StringHelper.equalsIgnoreCase(OxTrustConstants.RESULT_SUCCESS, resultInitContacts)) {
			return false;
		}

		String resultInitMetadataFilters = metadataFiltersAction.initMetadataFilters(this.trustRelationship);
		if (!StringHelper.equalsIgnoreCase(OxTrustConstants.RESULT_SUCCESS, resultInitMetadataFilters)) {
			return false;
		}

		String resultInitProfileConfigurations = relyingPartyAction.initProfileConfigurations();
		if (!StringHelper.equalsIgnoreCase(OxTrustConstants.RESULT_SUCCESS, resultInitProfileConfigurations)) {
			return false;
		}

		String resultInitFederationDeconstructions = federationDeconstructionAction.initFederationDeconstructions(this.trustRelationship);
		if (!StringHelper.equalsIgnoreCase(OxTrustConstants.RESULT_SUCCESS, resultInitFederationDeconstructions)) {
			return false;
		}

		initFederatedSites(this.trustRelationship);

		return true;
	}

	private List<GluuAttribute> getAllAttributes() {
		List<GluuAttribute> attributes = attributeService.getAllPersonAttributes(GluuUserRole.ADMIN);
		return attributes;
	}
	
	private List<GluuAttribute> getAllActiveAttributes() {
		List<GluuAttribute> attributes = attributeService.getAllActivePersonAttributes(GluuUserRole.ADMIN);
		return attributes;
	}

	private void initFederatedSites(GluuSAMLTrustRelationship trustRelationship) {
		List<GluuAttribute> attributes = getAllAttributes();

		this.federatedSites = new ArrayList<GluuSAMLTrustRelationship>();
		for (GluuSAMLTrustRelationship deconstructedTrustRelationship : trustService.getDeconstructedTrustRelationships(trustRelationship)) {
			initTrustRelationship(deconstructedTrustRelationship, attributes);
			this.federatedSites.add(deconstructedTrustRelationship);
		}
	}

	private void initAttributes(GluuSAMLTrustRelationship trust) {
		List<GluuAttribute> attributes = getAllActiveAttributes();
		List<String> origins = attributeService.getAllAttributeOrigins(attributes);

		initTrustRelationship(trust, attributes);

		customAttributeAction.initCustomAttributes(attributes, trust.getReleasedCustomAttributes(), origins, applicationConfiguration
				.getPersonObjectClassTypes(), applicationConfiguration.getPersonObjectClassDisplayNames());
	}

	public void initTrustRelationship(GluuSAMLTrustRelationship trust, List<GluuAttribute> attributes) {
		HashMap<String, GluuAttribute> attributesByDNs = attributeService.getAttributeMapByDNs(attributes);
		List<GluuCustomAttribute> customAttributes = attributeService.getCustomAttributesByAttributeDNs(trust.getReleasedAttributes(),
				attributesByDNs);
		boolean empty = (customAttributes == null) || customAttributes.isEmpty();
		if (empty) {
			customAttributes = new ArrayList<GluuCustomAttribute>();
		}

		trust.setReleasedCustomAttributes(customAttributes);
	}

	/**
	 * Sets entityId according to metadatafile. Works for all TR which have own
	 * metadata file.
	 * 
	 * @author �Oleksiy Tataryn�
	 */
	private void setEntityId() {

		String idpMetadataFolder = applicationConfiguration.getShibboleth3IdpRootDir() + File.separator	+ Shibboleth3ConfService.SHIB3_IDP_METADATA_FOLDER + File.separator;
		File metadataFile = new File(idpMetadataFolder + trustRelationship.getSpMetaDataFN());
		
		List<String> entityIdList = SAMLMetadataParser.getEntityIdFromMetadataFile(metadataFile);
		Set<String> entityIdSet = new TreeSet<String>();

		if(entityIdList != null && ! entityIdList.isEmpty()){
			Set<String> duplicatesSet = new TreeSet<String>(); 
			for (String entityId : entityIdList) {
				if (!entityIdSet.add(entityId)) {
					duplicatesSet.add(entityId);
				}
			}
		}

		this.trustRelationship.setGluuEntityId(entityIdSet);
	}

	/**
	 * If there is no certificate selected, or certificate is invalid -
	 * generates one.
	 * 
	 * @author �Oleksiy Tataryn�
	 * @return certificate for generated SP
	 * @throws CertificateEncodingException
	 */
	private String getCertForGeneratedSP() {

		X509Certificate cert = null;

		try {
			cert = SSLService.instance().getPEMCertificate(certWrapper.getStream());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		if (cert == null) {

			facesMessages.add(Severity.INFO, "Certificate were not provided, or was incorrect. Appliance will create a self-signed certificate.");
			if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
				Security.addProvider(new BouncyCastleProvider());
			}
			
			try {
				
				KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA", "BC"); 
				keyPairGen.initialize(2048); 
				KeyPair pair = keyPairGen.generateKeyPair(); 
				StringWriter keyWriter = new StringWriter(); 
				PEMWriter pemFormatWriter = new PEMWriter(keyWriter); 
				pemFormatWriter.writeObject(pair.getPrivate()); 
				pemFormatWriter.close(); 

				String url = trustRelationship.getUrl().replaceFirst(".*//", "");

				X509v3CertificateBuilder v3CertGen = new JcaX509v3CertificateBuilder(new X500Name("CN=" + url + ", OU=None, O=None L=None, C=None"),
																					 BigInteger.valueOf(new SecureRandom().nextInt()),
																					 new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30),
																					 new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365*10)),
																					 new X500Name("CN=" + url + ", OU=None, O=None L=None, C=None"),
																					 pair.getPublic());

				cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(v3CertGen.build(new JcaContentSignerBuilder("MD5withRSA").setProvider("BC").build(pair.getPrivate())));
				org.apache.commons.codec.binary.Base64 encoder = new org.apache.commons.codec.binary.Base64(64);
				byte[] derCert = cert.getEncoded();
				String pemCertPre = new String(encoder.encode(derCert));
				log.debug(Shibboleth3ConfService.PUBLIC_CERTIFICATE_START_LINE);
				log.debug(pemCertPre);
				log.debug(Shibboleth3ConfService.PUBLIC_CERTIFICATE_END_LINE);
			    
				saveCert(trustRelationship, pemCertPre);
				saveKey(trustRelationship, keyWriter.toString());
	    
			} catch (Exception e) {
				e.printStackTrace();
			}

//			String certName = applicationConfiguration.getCertDir() + File.separator + StringHelper.removePunctuation(applicationConfiguration.getOrgInum())
//					+ "-shib.crt";
//			File certFile = new File(certName);
//			if (certFile.exists()) {
//				cert = SSLService.instance().getPEMCertificate(certName);
//			}
		}

		String certificate = null;

		if (cert != null) {

			try {

				certificate = new String(Base64.encode(cert.getEncoded()));

				log.info("##### certificate = " + certificate);

			} catch (CertificateEncodingException e) {
				certificate = null;
				facesMessages.add(Severity.ERROR, "Failed to encode provided certificate. Please notify Gluu support about this.");
				log.error("Failed to encode certificate to DER", e);
			}

		} else {
			facesMessages.add(Severity.INFO, "Certificate were not provided, or was incorrect. Appliance will create a self-signed certificate.");
		}

		return certificate;
	}

	private void saveTR(boolean isUpdate) {
		log.trace("Saving Trust Relationship");
		if (isUpdate) {
			String oldLogoutRedirectUri = trustService.getRelationshipByDn(trustRelationship.getDn()).getSpLogoutURL();
			String newLogoutRedirectUri = trustRelationship.getSpLogoutURL();
			boolean oxClientUpdateNeeded = ! newLogoutRedirectUri.equals(oldLogoutRedirectUri);
			
			boolean parentInactive = trustRelationship.getStatus().equals(GluuStatus.INACTIVE);
			if(! federatedSites.isEmpty()){
				for (GluuSAMLTrustRelationship trust : federatedSites) {
					if (parentInactive) {
						trust.setStatus(GluuStatus.INACTIVE);
					}
					trustService.updateReleasedAttributes(trust);
					trustService.updateTrustRelationship(trust);
					svnSyncTimer.updateTrustRelationship(trust, identity.getCredentials().getUsername());
				}
			}
			trustService.updateTrustRelationship(this.trustRelationship);
			

			if(oxClientUpdateNeeded){
				OxAuthClient client = clientService.getClientByInum(applicationConfiguration.getOxAuthClientId());
				Set<String> updatedLogoutRedirectUris = new HashSet<String>();
				List<GluuSAMLTrustRelationship> trs = trustService.getAllTrustRelationships();
				if(trs != null && ! trs.isEmpty()){
					for(GluuSAMLTrustRelationship tr: trs){
						String logoutRedirectUri = tr.getSpLogoutURL();
						if(logoutRedirectUri != null && ! logoutRedirectUri.isEmpty()){
							updatedLogoutRedirectUris.add(logoutRedirectUri);
						}
					}
					
				}
				if(updatedLogoutRedirectUris.size() == 0){
					client.setPostLogoutRedirectUris(null);
				}else{
					client.setPostLogoutRedirectUris(updatedLogoutRedirectUris.toArray(new String[0]));
				}
				clientService.updateClient(client);
			}
			
			
			svnSyncTimer.updateTrustRelationship(this.trustRelationship, identity.getCredentials().getUsername());
		} else {
			trustService.addTrustRelationship(this.trustRelationship);
			svnSyncTimer.addTrustRelationship(this.trustRelationship, identity.getCredentials().getUsername());
		}
		
		
	}

	private void updateSpMetaDataCert(FileUploadWrapper certWrapper) {
		String certificate = shibboleth3ConfService.getPublicCertificate(certWrapper);
		if (certificate == null) {
			return;
		}
		// This regex defines certificate enclosed in X509Certificate tags
		// regardless of namespace(as long as it is not more then 9 characters)
		String certRegEx = "(?ms)(?<=<[^</>]{0,10}X509Certificate>).*(?=</[^</>]{0,10}?X509Certificate>)";
		try {
			saveCert(trustRelationship, certificate);
			saveKey(trustRelationship, null);
			
			String metadataFileName = this.trustRelationship.getSpMetaDataFN();
			File metadataFile = new File(shibboleth3ConfService.getSpMetadataFilePath(metadataFileName));
			String metadata = FileUtils.readFileToString(metadataFile);
			String updatedMetadata = metadata.replaceFirst(certRegEx, certificate);
			FileUtils.writeStringToFile(metadataFile, updatedMetadata);
			this.trustRelationship.setStatus(GluuStatus.ACTIVE);
		} catch (Exception e) {
			log.error("Failed to update certificate", e);
		}

	}

	/**
	 * @param trustRelationship
	 * @param certificate
	 */
	private void saveCert(GluuSAMLTrustRelationship trustRelationship,
			String certificate) {
		String sslDirFN = applicationConfiguration.getShibboleth3IdpRootDir()
				+ File.separator + TrustService.GENERATED_SSL_ARTIFACTS_DIR
				+ File.separator;
		File sslDir = new File(sslDirFN);
		if (!sslDir.exists()) {
			log.debug("creating directory: " + sslDirFN);
			boolean result = sslDir.mkdir();
			if (result) {
				log.debug("DIR created");

			}
		}
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(
			            new FileWriter(
			                       sslDirFN	
			                       + shibboleth3ConfService
			                           .getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$",".crt")));
			writer.write(Shibboleth3ConfService.PUBLIC_CERTIFICATE_START_LINE + "\n" 
						+ certificate
						+ Shibboleth3ConfService.PUBLIC_CERTIFICATE_END_LINE);
		} catch (IOException e) {
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
			}
		}

	}

	/**
	 * @param trustRelationship
	 * @param key
	 */
	private void saveKey(GluuSAMLTrustRelationship trustRelationship,
			String key) {
		
		
		String sslDirFN = applicationConfiguration.getShibboleth3IdpRootDir()
				+ File.separator + TrustService.GENERATED_SSL_ARTIFACTS_DIR
				+ File.separator;
		File sslDir = new File(sslDirFN);
		if (!sslDir.exists()) {
			log.debug("creating directory: " + sslDirFN);
			boolean result = sslDir.mkdir();
			if (result) {
				log.debug("DIR created");

			}
		}
		if(key != null){
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(sslDirFN	+ shibboleth3ConfService.getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$",".key")));
			writer.write(key);
		} catch (IOException e) {
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
			}
		}
		}else{
			File keyFile = new File(sslDirFN +  shibboleth3ConfService.getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$",".key"));
			if(keyFile.exists()){
				keyFile.delete();
			}
		}

	}

	private void markAsInactive() {
		// Mark this configuration as not active because we don't have correct
		// files in meta-data folder
		if (update) {
			try {
				GluuSAMLTrustRelationship tmpTrustRelationship = trustService.getRelationshipByInum(this.trustRelationship.getInum());
				tmpTrustRelationship.setStatus(GluuStatus.INACTIVE);
				saveTR(update);
			} catch (LdapMappingException ex) {
				log.error("Failed to update trust relationship {0}", ex, inum);
			}
		} else {
			// Remove file name to generate new one during new save attempt.
			// Cover case when somebody else added new one simultaneously
			this.trustRelationship.setSpMetaDataFN(null);
			this.trustRelationship.setInum(null);
		}
	}

	private void updateShibboleth3Configuration(List<GluuSAMLTrustRelationship> trustRelationships) {

		if (!shibboleth3ConfService.generateConfigurationFiles(trustRelationships)) {

			log.error("Failed to update Shibboleth v3 configuration");
			facesMessages.add(Severity.ERROR, "Failed to update Shibboleth v3 configuration");

		} else {

			log.info("Shibboleth v3 configuration updated successfully");
			facesMessages.add(Severity.INFO, "Shibboleth v3 configuration updated successfully");
			facesMessages.add(Severity.WARN, "Please note it may take several minutes before new settings are actually loaded and applied by Shibboleth module!");
		}
	}

	private boolean generateSpMetaDataFile(String certificate) {
		boolean result = generateSpMetaDataFileImpl(certificate);

		if (result) {
			this.trustRelationship.setSpMetaDataSourceType(GluuMetadataSourceType.FILE);
			facesMessages.add(Severity.WARN, "SP meta-data file generated.");
		} else {
			facesMessages.add(Severity.ERROR, "Failed to generate SP meta-data file");
			markAsInactive();
		}
		return result;
	}

	private boolean generateSpMetaDataFileImpl(String certificate) {
		String spMetadataFileName = trustRelationship.getSpMetaDataFN();

		if (StringHelper.isEmpty(spMetadataFileName)) {
			// Generate new file name
			spMetadataFileName = shibboleth3ConfService.getSpNewMetadataFileName(this.trustRelationship);
			trustRelationship.setSpMetaDataFN(spMetadataFileName);
		}

		return shibboleth3ConfService.generateSpMetadataFile(trustRelationship, certificate);
	}

	private boolean saveSpMetaDataFileSourceTypeFile() {
		log.trace("Saving metadata file source type: File");
		String spMetadataFileName = trustRelationship.getSpMetaDataFN();
		boolean emptySpMetadataFileName = StringHelper.isEmpty(spMetadataFileName);

		if (fileWrapper.getStream() == null) {
			if (emptySpMetadataFileName) {
				return false;
			}

			// Admin doesn't provide new file. Check if we already has this file
			String filePath = shibboleth3ConfService.getSpMetadataFilePath(spMetadataFileName);
			if (filePath == null) {
				return false;
			}

			File file = new File(filePath);
			if (!file.exists()) {
				return false;
			}

			// File already exist
			return true;
		}

		if (emptySpMetadataFileName) {
			// Generate new file name
			spMetadataFileName = shibboleth3ConfService.getSpNewMetadataFileName(this.trustRelationship);
			this.trustRelationship.setSpMetaDataFN(spMetadataFileName);
			if (trustRelationship.getDn() == null) {
				String dn = trustService.getDnForTrustRelationShip(this.inum);
				this.trustRelationship.setDn(dn);
				trustService.addTrustRelationship(this.trustRelationship);
			} else {
				trustService.updateTrustRelationship(this.trustRelationship);
			}
		}
		String result = shibboleth3ConfService.saveSpMetadataFile(spMetadataFileName, fileWrapper.getStream());
		if (StringHelper.isNotEmpty(result)) {
			MetadataValidationTimer.queue(result);
		} else {
			facesMessages.add(Severity.ERROR, "Failed to save SP meta-data file. Please check if you provide correct file");
		}

		return StringHelper.isNotEmpty(result);

	}

	public boolean saveSpMetaDataFileSourceTypeURI() throws IOException {
		String spMetadataFileName = trustRelationship.getSpMetaDataFN();
		boolean emptySpMetadataFileName = StringHelper.isEmpty(spMetadataFileName);

		if (emptySpMetadataFileName) {
			// Generate new file name
			spMetadataFileName = shibboleth3ConfService.getSpNewMetadataFileName(this.trustRelationship);
		}

		String result = shibboleth3ConfService.saveSpMetadataFile(trustRelationship.getSpMetaDataURL(), spMetadataFileName);
		if (StringHelper.isNotEmpty(result)) {
			MetadataValidationTimer.queue(result);
		} else {
			facesMessages.add(Severity.ERROR, "Failed to download metadata");
		}

		return StringHelper.isNotEmpty(result);
	}

	@Restrict("#{s:hasPermission('person', 'access')}")
	public String delete() {
		String result = OxTrustConstants.RESULT_FAILURE;
		if (update) {
			// Remove trust relationship
			try {
				synchronized (svnSyncTimer) {
					for (GluuSAMLTrustRelationship trust : trustService.getDeconstructedTrustRelationships(this.trustRelationship)) {
						if(GluuStatus.ACTIVE.equals(trust.getStatus())){
							log.error("Failed to remove federation trust relationship {0}, there are still active federated Trust Relationships left.", this.trustRelationship.getInum());
							return result;
						}
					}
					for (GluuSAMLTrustRelationship trust : trustService.getDeconstructedTrustRelationships(this.trustRelationship)) {
						trustService.removeTrustRelationship(trust);
						svnSyncTimer.removeTrustRelationship(trust, identity.getCredentials().getUsername());
					}
					shibboleth3ConfService.removeSpMetadataFile(this.trustRelationship.getSpMetaDataFN());
					trustService.removeTrustRelationship(this.trustRelationship);
					svnSyncTimer.removeTrustRelationship(this.trustRelationship, identity.getCredentials().getUsername());
				}
				result = OxTrustConstants.RESULT_SUCCESS;
			} catch (LdapMappingException ex) {
				result = OxTrustConstants.RESULT_FAILURE;
				log.error("Failed to remove trust relationship {0}", ex, this.trustRelationship.getInum());
			} catch (InterruptedException e) {
				log.error("Failed to add trust relationship to remove queue. It will be removed during next application restart", e);
			} finally {
				List<GluuSAMLTrustRelationship> trustRelationships = trustService.getAllActiveTrustRelationships();
				updateShibboleth3Configuration(trustRelationships);
			}
		}

		return result;
	}

	@Restrict("#{s:hasPermission('trust', 'access')}")
	public String downloadConfiguration() {
		Shibboleth3ConfService shibboleth3ConfService = Shibboleth3ConfService.instance();

		ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
		ZipOutputStream zos = ResponseHelper.createZipStream(bos, "Shibboleth v3 configuration files");
		try {
			zos.setMethod(ZipOutputStream.DEFLATED);
			zos.setLevel(Deflater.DEFAULT_COMPRESSION);

			// Add files
			String idpMetadataFilePath = shibboleth3ConfService.getIdpMetadataFilePath();
			if (!ResponseHelper.addFileToZip(idpMetadataFilePath, zos, Shibboleth3ConfService.SHIB3_IDP_IDP_METADATA_FILE)) {
				log.error("Failed to add " + idpMetadataFilePath + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}

			if (this.trustRelationship.getSpMetaDataFN() == null) {
				log.error("SpMetaDataFN is not set.");
				return OxTrustConstants.RESULT_FAILURE;
			}
			String spMetadataFilePath = shibboleth3ConfService.getSpMetadataFilePath(this.trustRelationship.getSpMetaDataFN());
			if (!ResponseHelper.addFileToZip(spMetadataFilePath, zos, Shibboleth3ConfService.SHIB3_IDP_SP_METADATA_FILE)) {
				log.error("Failed to add " + spMetadataFilePath + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}
			String sslDirFN = applicationConfiguration.getShibboleth3IdpRootDir() + File.separator + TrustService.GENERATED_SSL_ARTIFACTS_DIR + File.separator;
			String spKeyFilePath = sslDirFN + shibboleth3ConfService.getSpNewMetadataFileName(this.trustRelationship).replaceFirst("\\.xml$", ".key");
			if (!ResponseHelper.addFileToZip(spKeyFilePath, zos, Shibboleth3ConfService.SHIB3_IDP_SP_KEY_FILE)) {
				log.error("Failed to add " + spKeyFilePath + " to zip");
//				return OxTrustConstants.RESULT_FAILURE;
			}
			String spCertFilePath = sslDirFN + shibboleth3ConfService.getSpNewMetadataFileName(this.trustRelationship).replaceFirst("\\.xml$", ".crt");
			if (!ResponseHelper.addFileToZip(spCertFilePath, zos, Shibboleth3ConfService.SHIB3_IDP_SP_CERT_FILE)) {
				log.error("Failed to add " + spCertFilePath + " to zip");
//				return OxTrustConstants.RESULT_FAILURE;
			}

			String spAttributeMap = shibboleth3ConfService.generateSpAttributeMapFile(this.trustRelationship);
			if (spAttributeMap == null) {
				log.error("spAttributeMap is not set.");
				return OxTrustConstants.RESULT_FAILURE;
			}
			if (!ResponseHelper.addFileContentToZip(spAttributeMap, zos, Shibboleth3ConfService.SHIB3_SP_ATTRIBUTE_MAP_FILE)) {
				log.error("Failed to add " + spAttributeMap + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}

			VelocityContext context = new VelocityContext();

			context.put("spUrl", trustRelationship.getUrl());
			String gluuSPEntityId = trustRelationship.getEntityId();
			context.put("gluuSPEntityId", gluuSPEntityId);
			String spHost = trustRelationship.getUrl().replaceAll(":[0-9]*$", "").replaceAll("^.*?//", "");
			context.put("spHost", spHost);
			String idpUrl = applicationConfiguration.getIdpUrl();
			context.put("idpUrl", idpUrl);
			String idpHost = idpUrl.replaceAll(":[0-9]*$", "").replaceAll("^.*?//", "");
			context.put("idpHost", idpHost);
			context.put("orgInum", StringHelper.removePunctuation(OrganizationService.instance().getOrganizationInum()));
			context.put("orgSupportEmail", applicationConfiguration.getOrgSupportEmail());

			String spShibboleth3FilePath = shibboleth3ConfService.getSpShibboleth3FilePath();
			String shibConfig = templateService.generateConfFile(Shibboleth3ConfService.SHIB3_SP_SHIBBOLETH2_FILE, context);
			if (!ResponseHelper.addFileContentToZip(shibConfig, zos, Shibboleth3ConfService.SHIB3_SP_SHIBBOLETH2_FILE)) {
				log.error("Failed to add " + spShibboleth3FilePath + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}

			String spReadMeResourceName = shibboleth3ConfService.getSpReadMeResourceName();
			String fileName = (new File(spReadMeResourceName)).getName();
			InputStream is = resourceLoader.getResourceAsStream(spReadMeResourceName);
			//InputStream is = this.getClass().getClassLoader().getResourceAsStream(spReadMeResourceName);
			if (!ResponseHelper.addResourceToZip(is, fileName , zos)) {
				log.error("Failed to add " + spReadMeResourceName + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}

			String spReadMeWindowsResourceName = shibboleth3ConfService.getSpReadMeWindowsResourceName();
			fileName = (new File(spReadMeWindowsResourceName)).getName();
			is = resourceLoader.getResourceAsStream(spReadMeWindowsResourceName);
			if (!ResponseHelper.addResourceToZip(is, fileName , zos)) {
				log.error("Failed to add " + spReadMeWindowsResourceName + " to zip");
				return OxTrustConstants.RESULT_FAILURE;
			}

		} finally {
			IOUtils.closeQuietly(zos);
			IOUtils.closeQuietly(bos);
		}

		boolean result = ResponseHelper.downloadFile("shibboleth3-configuration.zip", OxTrustConstants.CONTENT_TYPE_APPLICATION_ZIP, bos.toByteArray(), facesContext);

		return result ? OxTrustConstants.RESULT_SUCCESS : OxTrustConstants.RESULT_FAILURE;
	}

	public FileUploadWrapper getFileWrapper() {
		return fileWrapper;
	}

	public FileUploadWrapper getCertWrapper() {
		return certWrapper;
	}

	private List<GluuCustomAttribute> getCurrentCustomAttributes() {
		List<GluuCustomAttribute> result = new ArrayList<GluuCustomAttribute>();
		if (selectedTR == null || selectedTR.equals(trustRelationship.getInum())) {
			result = this.trustRelationship.getReleasedCustomAttributes();
		} else {
			for (GluuSAMLTrustRelationship trust : federatedSites) {
				if (selectedTR.equals(trust.getInum())) {
					result = trust.getReleasedCustomAttributes();
					break;
				}
			}
		}

		return result;
	}

	public String getInum() {
		return inum;
	}

	public void setInum(String inum) {
		this.inum = inum;
	}

	public GluuSAMLTrustRelationship getTrustRelationship() {
		return trustRelationship;
	}

	public void loadMetadata() throws IOException {
		if (trustRelationship == null) {
			return ;
		}

		String filename = trustRelationship.getSpMetaDataFN();
		File metadataFile = null;
		if (!StringUtils.isEmpty(filename)) {
			metadataFile = new File(shibboleth3ConfService.getSpMetadataFilePath(filename));

			if (metadataFile.exists()) {
				metadata = FileUtils.readFileToString(metadataFile);
				return;
			}
		}

		return;
	}

	public boolean isUpdate() {
		return update;
	}

	protected String getEventQueue() {
		return "trustQueue";
	}

	protected String getActionName() {
		return "updateTrustRelationshipAction";
	}

	protected boolean allowAccessAttribute(GluuAttribute attribute) {
		return attribute.isAdminCanAccess();
	}

	protected boolean allowEditAttribute(GluuAttribute attribute) {
		// Allow select any attribute
		return true;
	}

	/*
	 * public void initReleasedAttributePanelBar(){ UIAccordion
	 * attributePanelBar = (UIAccordion)
	 * FacesComponentUtility.findComponentById("ReleasedAttributePanelBar"); if
	 * (attributePanelBar == null) { return; }
	 * 
	 * if (attributePanelBar.getChildCount() == 0) {
	 * initAttributePanelBar(attributePanelBar); } else {
	 * updateAttributePanelBar(attributePanelBar); } }
	 * 
	 * private void updateAttributePanelBar(UIAccordion attributePanelBar) {
	 * UIAccordionItem uiAccordionItem = null; Map<String, UIComponent> entities
	 * = new HashMap<String, UIComponent>();
	 * 
	 * for (UIComponent htmlComponent : attributePanelBar.getChildren()) {
	 * uiAccordionItem = (UIAccordionItem) htmlComponent;
	 * entities.put(uiAccordionItem.getId(), uiAccordionItem); }
	 * 
	 * // this.federatedSites.clear(); for(GluuSAMLTrustRelationship trust :
	 * trustService.getDeconstructedTrustRelationships(trustRelationship)){
	 * if(!this.federatedSites.contains(trust)){ this.federatedSites.add(trust);
	 * 
	 * if(!
	 * entities.keySet().contains(StringHelper.removePunctuation(trust.getInum
	 * ()))){ addNewPanelBarItem(attributePanelBar,trust); }else{ if(!
	 * entities.get
	 * (StringHelper.removePunctuation(trust.getInum())).isRendered()){
	 * entities.
	 * get(StringHelper.removePunctuation(trust.getInum())).setRendered(true); }
	 * } } entities.remove(StringHelper.removePunctuation(trust.getInum())); }
	 * 
	 * for(String entity : entities.keySet()){
	 * if(!entity.equals(StringHelper.removePunctuation
	 * (trustRelationship.getInum())) && !entity.equals("NewTrustRelationship")
	 * ){ if(entities.get(entity).isRendered()){
	 * entities.get(entity).setRendered(false); for(GluuSAMLTrustRelationship
	 * trust : this.federatedSites){
	 * if(entity.equals(StringHelper.removePunctuation(trust.getInum()))){
	 * this.federatedSites.remove(trust); break; } } } } } }
	 * 
	 * private void initAttributePanelBar(UIAccordion attributePanelBar) {
	 * UIAccordionItem federation = createTrustPanel(null); String
	 * federationName = trustRelationship.getDisplayName() == null ?
	 * "New Trust Relationship" : trustRelationship.getDisplayName();
	 * federation.setHeader(federationName); String federationId =
	 * trustRelationship.getInum() == null ? "NewTrustRelationship" :
	 * StringHelper.removePunctuation(trustRelationship.getInum());
	 * federation.setId("pb" + federationId);
	 * 
	 * attributePanelBar.getChildren().add(federation); this.federatedSites =
	 * new ArrayList<GluuSAMLTrustRelationship>(); for(GluuSAMLTrustRelationship
	 * trust :
	 * trustService.getDeconstructedTrustRelationships(trustRelationship)){
	 * addNewPanelBarItem(attributePanelBar,trust);
	 * 
	 * } }
	 * 
	 * private void addNewPanelBarItem(UIAccordion attributePanelBar,
	 * GluuSAMLTrustRelationship trust) { initAttributes(trust);
	 * federatedSites.add(trust); UIAccordionItem trustPanel =
	 * createTrustPanel(trust); attributePanelBar.getChildren().add(trustPanel);
	 * }
	 * 
	 * private UIAccordionItem createTrustPanel(GluuSAMLTrustRelationship trust)
	 * { Application application =
	 * FacesContext.getCurrentInstance().getApplication(); ExpressionFactory
	 * expressionFactory = application.getExpressionFactory(); ELContext
	 * elContext = FacesContext.getCurrentInstance().getELContext();
	 * 
	 * UIAccordionItem trustPanel = (UIAccordionItem)
	 * application.createComponent(UIAccordionItem.COMPONENT_TYPE); UIRepeat
	 * selectedCustomAttributes = (UIRepeat)
	 * application.createComponent(UIRepeat.COMPONENT_TYPE); if(trust == null){
	 * trustPanel.setHeader(trustRelationship.getDisplayName());
	 * trustPanel.setId
	 * ("pb"+StringHelper.removePunctuation(trustRelationship.getInum()));
	 * AjaxBehavior onEnter = (AjaxBehavior)
	 * application.createBehavior(AjaxBehavior.BEHAVIOR_ID); MethodExpression
	 * methodExpression = expressionFactory.createMethodExpression(elContext,
	 * "#{" + getActionName() + ".setSelectedTR('" + trustRelationship.getInum()
	 * + "')}", Void.TYPE, new Class[]{String.class});
	 * onEnter.setOnbeforesubmit(
	 * "changeButtonsAvailability('updateButtons',false);");
	 * onEnter.setOncomplete
	 * ("changeButtonsAvailability('updateButtons',true);");
	 * onEnter.setLimitRender(true);
	 * onEnter.setRender(Arrays.asList("attributeTabPanelGroupId"));
	 * 
	 * onEnter.addAjaxBehaviorListener(new
	 * MethodExpressionAjaxBehaviorListener(methodExpression));
	 * trustPanel.addClientBehavior("onenter", onEnter);
	 * 
	 * selectedCustomAttributes.setValue(trustRelationship.
	 * getReleasedCustomAttributes()); }else{
	 * trustPanel.setHeader(trust.getDisplayName());
	 * trustPanel.setId(StringHelper.removePunctuation(trust.getInum()));
	 * 
	 * AjaxBehavior onEnter = (AjaxBehavior)
	 * application.createBehavior(AjaxBehavior.BEHAVIOR_ID); MethodExpression
	 * methodExpression = expressionFactory.createMethodExpression(elContext,
	 * "#{" + getActionName() + ".setSelectedTR('" + trust.getEntityId() +
	 * "')}", Void.TYPE, new Class[]{String.class});
	 * onEnter.setOnbeforesubmit("changeButtonsAvailability('updateButtons',false);"
	 * );
	 * onEnter.setOncomplete("changeButtonsAvailability('updateButtons',true);"
	 * ); onEnter.setLimitRender(true);
	 * onEnter.setRender(Arrays.asList("attributeTabPanelGroupId"));
	 * onEnter.addAjaxBehaviorListener(new
	 * MethodExpressionAjaxBehaviorListener(methodExpression));
	 * trustPanel.addClientBehavior("onenter", onEnter);
	 * 
	 * initAttributes(trust);
	 * selectedCustomAttributes.setValue(trust.getReleasedCustomAttributes());
	 * 
	 * } //direction="bottom-left" mode="ajax" id="contactToolTip"
	 * horizontalOffset="200" eventsQueue="profileQueue"
	 * selectedCustomAttributes.setVar("_attribute"); HtmlCommandLink
	 * atributeName = (HtmlCommandLink)
	 * application.createComponent(HtmlCommandLink.COMPONENT_TYPE);
	 * ValueExpression bind = expressionFactory.createValueExpression(elContext,
	 * "#{_attribute.metadata.displayName}", String.class);
	 * atributeName.setValueExpression("value", bind); // Commented out during
	 * migration to Richfaces 4 //
	 * atributeName.setSimilarityGroupingId("_attribute");
	 * atributeName.setStyleClass("attributeTooltip"); ValueExpression
	 * samlUriBind = expressionFactory.createValueExpression(elContext,
	 * "SAML URI for this attribute: |#{" + getActionName() +
	 * ".getSAML1URI(_attribute.metadata)" + "}|#{" + getActionName() +
	 * ".getSAML2URI(_attribute.metadata)}", String.class);
	 * atributeName.setValueExpression("title", samlUriBind);
	 * 
	 * 
	 * selectedCustomAttributes.getChildren().add(atributeName); HtmlOutputText
	 * spacer = (HtmlOutputText)
	 * application.createComponent(HtmlOutputText.COMPONENT_TYPE);
	 * spacer.setValue(" "); selectedCustomAttributes.getChildren().add(spacer);
	 * UICommandLink commandLink = (UICommandLink)
	 * application.createComponent(UICommandLink.COMPONENT_TYPE);
	 * MethodExpression removeMethodExpression =
	 * expressionFactory.createMethodExpression(elContext, "#{" +
	 * getActionName() + ".removeCustomAttribute(_attribute.metadata.inum)}",
	 * null, NO_PARAM_SIGNATURE);
	 * commandLink.setActionExpression(removeMethodExpression);
	 * 
	 * commandLink.setRender("ReleasedAttributePanelBar, attributeTabPanelGroupId"
	 * ); commandLink.setOncomplete("addTooltip();");
	 * commandLink.setLimitRender(true);
	 * 
	 * HtmlGraphicImage image = (HtmlGraphicImage)
	 * application.createComponent(HtmlGraphicImage.COMPONENT_TYPE);
	 * image.setValue("/img/remove.gif"); commandLink.getChildren().add(image);
	 * 
	 * 
	 * 
	 * selectedCustomAttributes.getChildren().add(commandLink); HtmlOutputText
	 * lineBreak = (HtmlOutputText)
	 * application.createComponent(HtmlOutputText.COMPONENT_TYPE);
	 * lineBreak.setValue("<br/>"); lineBreak.setEscape(false);
	 * selectedCustomAttributes.getChildren().add(lineBreak);
	 * trustPanel.getChildren().add(selectedCustomAttributes);
	 * 
	 * return trustPanel; }
	 */
	public String getSAML1URI(GluuAttribute attribute) {
		if (StringHelper.isNotEmpty(attribute.getSaml1Uri())) {
			return "SAML1 URI: " + attribute.getSaml1Uri();
		}
		String namespace = "";
		if (attribute.isCustom() || StringHelper.isEmpty(attribute.getUrn())
				|| (!StringHelper.isEmpty(attribute.getUrn()) && attribute.getUrn().startsWith("urn:gluu:dir:attribute-def:"))) {
			namespace = "gluu";
		} else {
			namespace = "mace";
		}

		return "SAML1 URI: urn:" + namespace + ":dir:attribute-def:" + attribute.getName();
	}

	public String getSAML2URI(GluuAttribute attribute) {
		if (StringHelper.isNotEmpty(attribute.getSaml2Uri())) {
			return "SAML1 URI: " + attribute.getSaml2Uri();
		}
		List<String> attributeNames = new ArrayList<String>();
		attributeNames.add(attribute.getName());
		SchemaService shemaService = SchemaService.instance();
		SchemaEntry schemaEntry = shemaService.getSchema();
		List<AttributeTypeDefinition> attributeTypes = shemaService.getAttributeTypeDefinitions(schemaEntry, attributeNames);
		String attributeName = attribute.getName();

		AttributeTypeDefinition attributeTypeDefinition = shemaService.getAttributeTypeDefinition(attributeTypes, attributeName);
		if (attributeTypeDefinition == null) {
			log.error("Failed to get OID for attribute name {0}", attributeName);
			return null;
		}

		return "SAML2 URI: urn:oid:" + attributeTypeDefinition.getOID();
	}

	public void setSelectedTR(String trust) {
		this.selectedTR = trust;
		customAttributeAction.refreshCustomAttributes(getCurrentCustomAttributes());
	}

	public void setContainerFederation(SelectItem federation) {
		this.trustRelationship.setContainerFederation((GluuSAMLTrustRelationship) federation.getValue());
	}

	public SelectItem getContainerFederation() {
		return new SelectItem(trustRelationship.getContainerFederation(),
				trustRelationship.getContainerFederation() == null ? "Select Federation" : trustRelationship.getContainerFederation()
						.getDisplayName());
	}

	public ArrayList<SelectItem> getAllFederations() {
		ArrayList<SelectItem> result = new ArrayList<SelectItem>();
		for (GluuSAMLTrustRelationship federation : trustService.getAllFederations()) {
			result.add(new SelectItem(federation, federation.getDisplayName()));
		}
		return result;
	}

	public boolean isActive() {
		return GluuStatus.ACTIVE.equals(trustRelationship.getStatus());
	}

	public String activationToggle() {
		if (trustRelationship.getStatus().equals(GluuStatus.ACTIVE)) {
			trustRelationship.setStatus(GluuStatus.INACTIVE);
		} else if (trustRelationship.getStatus().equals(GluuStatus.INACTIVE)) {
			trustRelationship.setStatus(GluuStatus.ACTIVE);
		}
		saveTR(true);

		List<GluuSAMLTrustRelationship> trustRelationships = trustService.getAllActiveTrustRelationships();
		updateShibboleth3Configuration(trustRelationships);

		return OxTrustConstants.RESULT_SUCCESS;
	}

	public void setSelectedEntities(String[] entities) {
		if (entities != null && entities.length > 0) {
			this.trustRelationship.setEntityId(entities[0]);
		}
	}

	public String[] getSelectedEntities() {
		if (isUpdate() && this.trustRelationship.getGluuEntityId() != null) {
			return this.trustRelationship.getGluuEntityId().toArray(new String[0]);
		} else {
			return new String[0];
		}

	}

	public void filterEntities() {
		filteredEntities = null;
		if (StringHelper.isNotEmpty(getFilterString())) {
			filteredEntities = new ArrayList<String>();
			for (String entity : trustRelationship.getContainerFederation().getGluuEntityId()) {
				if (entity.toLowerCase().contains(getFilterString().toLowerCase())) {
					filteredEntities.add(entity);
				}
			}
		}
	}

	public void setAvailableEntities(List<String> availableEntities) {

		this.availableEntities.removeAll(availableEntitiesFiltered);
		this.availableEntities.addAll(availableEntities);
	}

	public List<String> getAvailableEntities() {
		if (trustRelationship.getContainerFederation() == null) {
			return null;
		} else {
			if (!trustRelationship.getContainerFederation().getGluuEntityId().contains(trustRelationship.getEntityId())) {
				trustRelationship.setEntityId(null);
				availableEntities = null;
			}
		}

		if (availableEntities == null) {
			availableEntities = new ArrayList<String>();
			if (trustRelationship.getContainerFederation() != null) {
				availableEntities.addAll(trustRelationship.getContainerFederation().getGluuEntityId());
			}

		}
		availableEntitiesFiltered = new ArrayList<String>();
		availableEntitiesFiltered.addAll(availableEntities);

		if (filteredEntities != null) {
			availableEntitiesFiltered.retainAll(filteredEntities);

		}
		return availableEntitiesFiltered;
	}

	public void setFilterString(String filterString) {
		this.filterString = filterString;
	}

	public String getFilterString() {
		return this.filterString;
	}

	public List<GluuSAMLTrustRelationship> getFederatedSites() {
		return federatedSites;
	}

	public GluuEntityType[] getEntityTypeList() {
		return GluuEntityType.values();
	}
}
