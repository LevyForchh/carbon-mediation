/*
 *  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.message.store;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.deployment.DeploymentEngine;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.config.xml.MultiXMLConfigurationBuilder;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.deployers.MessageStoreDeployer;
import org.apache.synapse.deployers.SynapseArtifactDeploymentStore;
import org.apache.synapse.message.store.MessageStore;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.mediation.dependency.mgt.services.DependencyManagementService;
import org.wso2.carbon.mediation.initializer.ServiceBusConstants;
import org.wso2.carbon.mediation.initializer.ServiceBusUtils;
import org.wso2.carbon.mediation.initializer.services.SynapseConfigurationService;
import org.wso2.carbon.mediation.initializer.services.SynapseEnvironmentService;
import org.wso2.carbon.mediation.initializer.services.SynapseRegistrationsService;
import org.wso2.carbon.message.store.service.MessageStoreDeployerService;
import org.wso2.carbon.message.store.service.MessageStoreDeployerServiceImpl;
import org.wso2.carbon.message.store.util.ConfigHolder;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.service.mgt.ServiceAdmin;
import org.wso2.carbon.utils.AbstractAxis2ConfigurationContextObserver;
import org.wso2.carbon.utils.Axis2ConfigurationContextObserver;
import org.wso2.carbon.utils.ConfigurationContextService;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

@SuppressWarnings({"UnusedDeclaration"})
@Component(
        name = "org.wso2.carbon.messageStore",
        immediate = true)
public class MessageStoreAdminServiceComponent extends AbstractAxis2ConfigurationContextObserver {

    private static Log log = LogFactory.getLog(MessageStoreAdminServiceComponent.class);

    private boolean activated = false;

    @Activate
    protected void activate(ComponentContext ctxt) {

        try {
            BundleContext bndCtx = ctxt.getBundleContext();
            bndCtx.registerService(Axis2ConfigurationContextObserver.class.getName(), this, null);
            bndCtx.registerService(MessageStoreDeployerService.class.getName(), new MessageStoreDeployerServiceImpl()
                    , null);
            SynapseEnvironmentService synEnvService = ConfigHolder.getInstance().getSynapseEnvironmentService
                    (MultitenantConstants.SUPER_TENANT_ID);
            registerDeployer(ConfigHolder.getInstance().getAxisConfiguration(), synEnvService.getSynapseEnvironment());
            if (log.isDebugEnabled()) {
                log.debug("Message Store Admin bundle is activated for Super tenant");
            }
            activated = true;
        } catch (Throwable e) {
            log.error("Failed to activate Message Store Admin bundle for Super tenant ", e);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        try {
            Set<Map.Entry<Integer, SynapseEnvironmentService>> entrySet = ConfigHolder.getInstance()
                    .getSynapseEnvironmentServices().entrySet();
            for (Map.Entry<Integer, SynapseEnvironmentService> entry : entrySet) {
                unregisterDeployer(entry.getValue().getConfigurationContext().getAxisConfiguration(), entry.getValue
                        ().getSynapseEnvironment());
            }
        } catch (Exception e) {
            log.warn("Couldn't remove the Message Store Deployer");
        }
    }

    @Reference(
            name = "configuration.context.service",
            service = org.wso2.carbon.utils.ConfigurationContextService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetConfigurationContextService")
    protected void setConfigurationContextService(ConfigurationContextService cfgCtxService) {

        ConfigHolder.getInstance().setAxisConfiguration(cfgCtxService.getServerConfigContext().getAxisConfiguration());
    }

    protected void unsetConfigurationContextService(ConfigurationContextService cfgCtxService) {

        ConfigHolder.getInstance().setAxisConfiguration(null);
    }

    @Reference(
            name = "synapse.config.service",
            service = org.wso2.carbon.mediation.initializer.services.SynapseConfigurationService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetSynapseConfigurationService")
    protected void setSynapseConfigurationService(SynapseConfigurationService synapseConfigurationService) {

        ConfigHolder.getInstance().setSynapseConfiguration(synapseConfigurationService.getSynapseConfiguration());
    }

    protected void unsetSynapseConfigurationService(SynapseConfigurationService synapseConfigurationService) {

        ConfigHolder.getInstance().setSynapseConfiguration(null);
    }

    /**
     * Here we receive an event about the creation of a SynapseEnvironment. If this is
     * SuperTenant we have to wait until all the other constraints are met and actual
     * initialization is done in the activate method. Otherwise we have to do the activation here.
     *
     * @param synapseEnvironmentService SynapseEnvironmentService which contains information
     *                                  about the new Synapse Instance
     */
    @Reference(
            name = "synapse.env.service",
            service = org.wso2.carbon.mediation.initializer.services.SynapseEnvironmentService.class,
            cardinality = ReferenceCardinality.AT_LEAST_ONE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetSynapseEnvironmentService")
    protected void setSynapseEnvironmentService(SynapseEnvironmentService synapseEnvironmentService) {

        boolean alreadyCreated = ConfigHolder.getInstance().getSynapseEnvironmentServices().containsKey
                (synapseEnvironmentService.getTenantId());
        ConfigHolder.getInstance().addSynapseEnvironmentService(synapseEnvironmentService.getTenantId(),
                synapseEnvironmentService);
        if (activated) {
            if (!alreadyCreated) {
                try {
                    registerDeployer(synapseEnvironmentService.getConfigurationContext().getAxisConfiguration(),
                            synapseEnvironmentService.getSynapseEnvironment());
                    if (log.isDebugEnabled()) {
                        log.debug("Message Store Admin bundle is activated for tenant");
                    }
                } catch (Throwable e) {
                    log.error("Failed to activate Message Store Admin bundle for tenant ", e);
                }
            }
        }
    }

    /**
     * Here we receive an event about Destroying a SynapseEnvironment. This can be the super tenant
     * destruction or a tenant destruction.
     *
     * @param synapseEnvironmentService synapseEnvironment
     */
    protected void unsetSynapseEnvironmentService(SynapseEnvironmentService synapseEnvironmentService) {

        ConfigHolder.getInstance().removeSynapseEnvironmentService(synapseEnvironmentService.getTenantId());
    }

    @Reference(
            name = "registry.service",
            service = org.wso2.carbon.registry.core.service.RegistryService.class,
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetRegistryService")
    protected void setRegistryService(RegistryService regService) {

        if (log.isDebugEnabled()) {
            log.debug("RegistryService bound to the ESB initialization process");
        }
        try {
            ConfigHolder.getInstance().setRegistry(regService.getConfigSystemRegistry());
        } catch (RegistryException e) {
            log.error("Couldn't retrieve the registry from the registry service");
        }
    }

    @Reference(
            name = "synapse.registrations.service",
            service = org.wso2.carbon.mediation.initializer.services.SynapseRegistrationsService.class,
            cardinality = ReferenceCardinality.AT_LEAST_ONE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetSynapseRegistrationsService")
    protected void setSynapseRegistrationsService(SynapseRegistrationsService synapseRegistrationsService) {

    }

    protected void unsetSynapseRegistrationsService(SynapseRegistrationsService synapseRegistrationsService) {

        int tenantId = synapseRegistrationsService.getTenantId();
        if (ConfigHolder.getInstance().getSynapseEnvironmentServices().containsKey(tenantId)) {
            SynapseEnvironment env = ConfigHolder.getInstance().getSynapseEnvironmentService(tenantId)
                    .getSynapseEnvironment();
            ConfigHolder.getInstance().removeSynapseEnvironmentService(synapseRegistrationsService.getTenantId());
            AxisConfiguration axisConfig = synapseRegistrationsService.getConfigurationContext().getAxisConfiguration();
            if (axisConfig != null) {
                unregisterDeployer(axisConfig, env);
            }
        }
    }

    @Reference(
            name = "service.admin.service",
            service = org.wso2.carbon.service.mgt.ServiceAdmin.class,
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetServiceAdminService")
    protected void setServiceAdminService(ServiceAdmin service) {

    }

    protected void unsetServiceAdminService(ServiceAdmin service) {

    }

    protected void unsetRegistryService(RegistryService regService) {

        if (log.isDebugEnabled()) {
            log.debug("RegistryService unbound from the ESB environment");
        }
        ConfigHolder.getInstance().setRegistry(null);
    }

    @Reference(
            name = "dependency.mgt.service",
            service = org.wso2.carbon.mediation.dependency.mgt.services.DependencyManagementService.class,
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetDependencyManager")
    protected void setDependencyManager(DependencyManagementService dependencyMgr) {

        if (log.isDebugEnabled()) {
            log.debug("Dependency management service bound to the endpoint component");
        }
        ConfigHolder.getInstance().setDependencyManager(dependencyMgr);
    }

    protected void unsetDependencyManager(DependencyManagementService dependencyMgr) {

        if (log.isDebugEnabled()) {
            log.debug("Dependency management service unbound from the endpoint component");
        }
        ConfigHolder.getInstance().setDependencyManager(null);
    }

    /**
     * Registers the Message Store Deployer.
     *
     * @param axisConfig         AxisConfiguration to which this deployer belongs
     * @param synapseEnvironment SynapseEnvironment to which this deployer belongs
     */
    private void registerDeployer(AxisConfiguration axisConfig, SynapseEnvironment synapseEnvironment) {

        SynapseConfiguration synCfg = synapseEnvironment.getSynapseConfiguration();
        DeploymentEngine deploymentEngine = (DeploymentEngine) axisConfig.getConfigurator();
        SynapseArtifactDeploymentStore deploymentStore = synCfg.getArtifactDeploymentStore();
        String synapseConfigPath = ServiceBusUtils.getSynapseConfigAbsPath(synapseEnvironment
                .getServerContextInformation());
        String messageStoreDirPath = synapseConfigPath + File.separator + MultiXMLConfigurationBuilder
                .MESSAGE_STORE_DIR;
        for (MessageStore messageStore : synCfg.getMessageStores().values()) {
            if (messageStore.getFileName() != null) {
                deploymentStore.addRestoredArtifact(messageStoreDirPath + File.separator + messageStore.getFileName());
            }
        }
        synchronized (axisConfig) {
            deploymentEngine.addDeployer(new MessageStoreDeployer(), messageStoreDirPath, ServiceBusConstants
                    .ARTIFACT_EXTENSION);
        }
    }

    /**
     * Un-registers the Message Store Deployer.
     *
     * @param axisConfig         AxisConfiguration to which this deployer belongs
     * @param synapseEnvironment SynapseEnvironment to which this deployer belongs
     */
    private void unregisterDeployer(AxisConfiguration axisConfig, SynapseEnvironment synapseEnvironment) {

        if (axisConfig != null) {
            DeploymentEngine deploymentEngine = (DeploymentEngine) axisConfig.getConfigurator();
            String synapseConfigPath = ServiceBusUtils.getSynapseConfigAbsPath(synapseEnvironment
                    .getServerContextInformation());
            String storeDirPath = synapseConfigPath + File.separator + MultiXMLConfigurationBuilder.MESSAGE_STORE_DIR;
            deploymentEngine.removeDeployer(storeDirPath, ServiceBusConstants.ARTIFACT_EXTENSION);
        }
    }

    public void createdConfigurationContext(ConfigurationContext configContext) {

        AxisConfiguration axisConfig = configContext.getAxisConfiguration();
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (axisConfig != null) {
            SynapseEnvironmentService synEnvService = ConfigHolder.getInstance().getSynapseEnvironmentService(tenantId);
            if (synEnvService != null) {
                try {
                    registerDeployer(axisConfig, synEnvService.getSynapseEnvironment());
                } catch (Exception e) {
                    log.error("Error while initializing MessageStore Admin", e);
                }
            }
        }
    }
}
