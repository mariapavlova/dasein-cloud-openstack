/**
 * Copyright (C) 2009-2015 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.openstack.nova.os.compute;

import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.AbstractVolumeSupport;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.Volume;
import org.dasein.cloud.compute.VolumeCapabilities;
import org.dasein.cloud.compute.VolumeCreateOptions;
import org.dasein.cloud.compute.VolumeFormat;
import org.dasein.cloud.compute.VolumeProduct;
import org.dasein.cloud.compute.VolumeState;
import org.dasein.cloud.compute.VolumeType;
import org.dasein.cloud.openstack.nova.os.NovaMethod;
import org.dasein.cloud.openstack.nova.os.NovaOpenStack;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Storage;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Support for the Cinder volumes API in Dasein Cloud.
 * <p>Created by George Reese: 10/25/12 9:22 AM</p>
 * @author George Reese
 * @version 2012.09.1 copied over from volume extension for HP
 * @since 2012.09.1
 */
public class CinderVolume extends AbstractVolumeSupport<NovaOpenStack> {
    static private final Logger logger = NovaOpenStack.getLogger(CinderVolume.class, "std");

    static public final String SERVICE  = "volume";

    public CinderVolume(@Nonnull NovaOpenStack provider) {
        super(provider);
    }

    @Nonnull String getAttachmentsResource() {
        return "os-volume_attachments";
    }

    private @Nonnull String getResource() {
        // 20130930 dmayne: seems like hp may have upgraded and uses the same resource
        return "/volumes";
        // return ((getProvider()).isHP() ? "/os-volumes" : "/volumes");
    }

    @Nonnull String getTypesResource() {
        return "/types";
    }

    @Override
    public void attach(@Nonnull String volumeId, @Nonnull String toServer, @Nonnull String device) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.attach");
        try {
            Map<String,Object> attachment = new HashMap<>();
            Map<String,Object> wrapper = new HashMap<>();
            NovaMethod method = new NovaMethod(getProvider());

            attachment.put("volumeId", volumeId);
            attachment.put("device", device);
            wrapper.put("volumeAttachment", attachment);

            if( method.postString(NovaServer.SERVICE, "/servers", toServer, getAttachmentsResource(), new JSONObject(wrapper)) == null ) {
                throw new CommunicationException("No response from the cloud");
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String createVolume(@Nonnull VolumeCreateOptions options) throws InternalException, CloudException {
        if( options.getVlanId() != null ) {
            throw new OperationNotSupportedException("Creating NFS volumes is not supported in " + getProvider().getCloudName());
        }
        APITrace.begin(getProvider(), "Volume.createVolume");
        try {
            Map<String,Object> wrapper = new HashMap<>();
            Map<String,Object> json = new HashMap<>();
            NovaMethod method = new NovaMethod(getProvider());

            json.put("display_name", options.getName());
            json.put("display_description", options.getDescription());
            /*if( !options.getDataCenterId()
                    .equals(getProvider().getContext().getRegionId() + "-a") ) {
                json.put("availability_zone", options.getDataCenterId());
            }*/

            Storage<Gigabyte> size = options.getVolumeSize();

            if( size == null || (size.intValue() < getCapabilities().getMinimumVolumeSize().intValue()) ) {
                size = getCapabilities().getMinimumVolumeSize();
            }
            else if( getCapabilities().getMaximumVolumeSize() != null && size.intValue() > getCapabilities().getMaximumVolumeSize().intValue() ) {
                size = getCapabilities().getMaximumVolumeSize();
            }
            json.put("size", size.intValue());
            if( options.getSnapshotId() != null ) {
                json.put("snapshot_id", options.getSnapshotId());
            }
            Map<String,Object> md = options.getMetaData();

            if( md != null && !md.isEmpty() ) {
                json.put("metadata", md);
            }
            if( options.getVolumeProductId() != null ) {
                // TODO: cinder was broken and expected the name prior Grizzly
                json.put("volume_type", options.getVolumeProductId());
            }
            wrapper.put("volume", json);
            JSONObject result = method.postString(SERVICE, getResource(), null, new JSONObject(wrapper), true);

            if( result != null && result.has("volume") ) {
                try {
                    Volume volume = toVolume(result.getJSONObject("volume"), listVolumeProducts());

                    if( volume != null ) {
                        return volume.getProviderVolumeId();
                    }
                }
                catch( JSONException e ) {
                    logger.error("create(): Unable to understand create response: " + e.getMessage());
                    throw new CommunicationException("Unable to understand createVolume response: " + e.getMessage(), e);
                }
            }
            logger.error("create(): No volume was created by the create attempt, and no error was returned");
            throw new ResourceNotFoundException("volume", options.getVolumeProductId());

        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void detach(@Nonnull String volumeId, boolean force) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.detach");
        try {
            Volume volume = getVolume(volumeId);

            if( volume == null ) {
                throw new ResourceNotFoundException("volume", volumeId);
            }
            if( volume.getProviderVirtualMachineId() == null ) {
                throw new ResourceNotFoundException("Volume ", volumeId + " is not attached");
            }
            NovaMethod method = new NovaMethod(getProvider());

            method.deleteResource(NovaServer.SERVICE, "/servers", volume.getProviderVirtualMachineId(), getAttachmentsResource() + "/" + volumeId);
        }
        finally {
            APITrace.end();
        }
    }

    private transient volatile CinderVolumeCapabilities capabilities;

    @Override
    public VolumeCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new CinderVolumeCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public @Nullable Volume getVolume(@Nonnull String volumeId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.getVolume");
        try {
            NovaMethod method = new NovaMethod(getProvider());
            JSONObject ob = method.getResource(SERVICE, getResource(), volumeId, true);

            if( ob == null ) {
                return null;
            }
            try {
                if( ob.has("volume") ) {
                    return toVolume(ob.getJSONObject("volume"), listVolumeProducts());
                }
            }
            catch( JSONException e ) {
                logger.error("getVolume(): Unable to identify expected values in JSON: " + e.getMessage());
                throw new CommunicationException("Unable to understand getVolume response: " + e.getMessage(), e);
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    private Iterable<VolumeProduct> getCachedProducts() throws InternalException {
        Cache<VolumeProduct> cache = Cache.getInstance(getProvider(), "volumeProducts", VolumeProduct.class, CacheLevel.REGION_ACCOUNT);
        return cache.get(getContext());
    }

    private void saveProductsToCache(Iterable<VolumeProduct> products) throws InternalException {
        Cache<VolumeProduct> cache = Cache.getInstance(getProvider(), "volumeProducts", VolumeProduct.class, CacheLevel.REGION_ACCOUNT);
        cache.put(getContext(), products);
    }

    @Override
    public @Nonnull Iterable<VolumeProduct> listVolumeProducts() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumeProducts");
        try {
            Iterable<VolumeProduct> cached = getCachedProducts();
            if( cached != null ) {
                return cached;
            }
            NovaMethod method = new NovaMethod(getProvider());
            List<VolumeProduct> products = new ArrayList<>();

            JSONObject json = method.getResource(SERVICE, getTypesResource(), null, false);

            if( json != null && json.has("volume_types") ) {
                try {
                    JSONArray list = json.getJSONArray("volume_types");

                    for( int i=0; i<list.length(); i++ ) {
                        JSONObject t = list.getJSONObject(i);
                        String name = (t.has("name") ? t.getString("name") : null);
                        String id = (t.has("id") ? t.getString("id") : null);
                        JSONObject specs = (t.has("extra_specs") ? t.getJSONObject("extra_specs") : null);

                        if( name == null || id == null ) {
                            continue;
                        }
                        // this is a huge ass guess
                        VolumeType type = (name.toLowerCase().contains("ssd") ? VolumeType.SSD : VolumeType.HDD);

                        if( specs != null ) {
                            String[] names = JSONObject.getNames(specs);

                            if( names != null && names.length > 0 ) {
                                for( String field : names ) {
                                    if( specs.has(field) && specs.get(field) instanceof String ) {
                                        String value = specs.getString(field);

                                        if( value != null && value.toLowerCase().contains("ssd") ) {
                                            type = VolumeType.SSD;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        products.add(VolumeProduct.getInstance(id, name, name, type));
                    }
                }
                catch( JSONException e ) {
                    logger.error("listVolumes(): Unable to identify expected values in JSON: " + e.getMessage());
                    throw new CommunicationException("Unable to understand listVolumes response: " + e.getMessage(), e);
                }
            }
            saveProductsToCache(products);
            return products;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<ResourceStatus> listVolumeStatus() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumeStatus");
        try {
            NovaMethod method = new NovaMethod(getProvider());
            List<ResourceStatus> volumes = new ArrayList<>();

            JSONObject json = method.getResource(SERVICE, getResource(), null, false);

            if( json != null && json.has("volumes") ) {
                try {
                    JSONArray list = json.getJSONArray("volumes");

                    for( int i=0; i<list.length(); i++ ) {
                        ResourceStatus volume = toStatus(list.getJSONObject(i));

                        if( volume != null ) {
                            volumes.add(volume);
                        }
                    }
                }
                catch( JSONException e ) {
                    logger.error("listVolumes(): Unable to identify expected values in JSON: " + e.getMessage());
                    throw new CommunicationException("Unable to understand listVolumeStatus response: " + e.getMessage(), e);
                }
            }
            return volumes;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<Volume> listVolumes() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumes");
        try {
            Iterable<VolumeProduct> products = listVolumeProducts();
            NovaMethod method = new NovaMethod(getProvider());
            List<Volume> volumes = new ArrayList<>();

            JSONObject json = method.getResource(SERVICE, getResource(), null, false);

            if( json != null && json.has("volumes") ) {
                try {
                    JSONArray list = json.getJSONArray("volumes");

                    for( int i=0; i<list.length(); i++ ) {
                        JSONObject v = list.getJSONObject(i);
                        Volume volume = toVolume(v, products);

                        if( volume != null ) {
                            volumes.add(volume);
                        }
                    }
                }
                catch( JSONException e ) {
                    logger.error("listVolumes(): Unable to identify expected values in JSON: " + e.getMessage());
                    throw new CommunicationException("Unable to understand listVolumes response: " + e.getMessage(), e);
                }
            }
            return volumes;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Volume.isSubscribed");
        try {
            return getProvider().getAuthenticationContext().getServiceUrl(SERVICE) != null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void remove(@Nonnull String volumeId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.remove");
        try {
            long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 10L);
            Volume v = getVolume(volumeId);

            while( timeout > System.currentTimeMillis() ) {
                if( v == null ) {
                    return;
                }
                if( !VolumeState.PENDING.equals(v.getCurrentState()) ) {
                    break;
                }
                try { Thread.sleep(15000L); }
                catch( InterruptedException ignore ) { }
                try {
                    v = getVolume(volumeId);
                }
                catch( Throwable ignore ) {
                    // ignore
                }
            }
            NovaMethod method = new NovaMethod(getProvider());

            method.deleteResource(SERVICE, getResource(), volumeId, null);

            v = getVolume(volumeId);
            timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 10L);
            while( timeout > System.currentTimeMillis() ) {
                if( v == null || v.getCurrentState().equals(VolumeState.DELETED)) {
                    return;
                }
                try { Thread.sleep(15000L); }
                catch( InterruptedException ignore ) { }
                try {
                    v = getVolume(volumeId);
                }
                catch( Throwable ignore ) {
                    // ignore
                }
            }
            logger.warn("Volume remove op accepted but still available: current state - "+v.getCurrentState());
        }
        finally {
            APITrace.end();
        }
    }

    protected  @Nullable ResourceStatus toStatus(@Nullable JSONObject json) throws CloudException, InternalException {
        if( json == null ) {
            return null;
        }
        try {
            String volumeId = null;

            if( json.has("id") ) {
                volumeId = json.getString("id");
            }
            if( volumeId == null ) {
                return null;
            }

            VolumeState state = VolumeState.PENDING;

            if( json.has("status") ) {
                String status = json.getString("status");

                if( status.equalsIgnoreCase("available") ) {
                    state = VolumeState.AVAILABLE;
                }
                else if( status.equalsIgnoreCase("creating") ) {
                    state = VolumeState.PENDING;
                }
                else if( status.equalsIgnoreCase("error") ) {
                    state = VolumeState.ERROR;
                }
                else if( status.equals("in-use") ) {
                    state = VolumeState.AVAILABLE;
                }
                else if( status.equals("attaching") ) {
                    state = VolumeState.PENDING;
                }
                else {
                    logger.warn("DEBUG: Unknown OpenStack Cinder volume state: " + status);
                }
            }
            return new ResourceStatus(volumeId, state);
        }
        catch( JSONException e ) {
            throw new CommunicationException("Unable to understand toStatus response: " + e.getMessage(), e);
        }
    }

    protected  @Nullable Volume toVolume(@Nullable JSONObject json, @Nonnull Iterable<VolumeProduct> types) throws CloudException, InternalException {
        if( json == null ) {
            return null;
        }
        try {
            String volumeId = null;
            if( json.has("id") ) {
                volumeId = json.getString("id");
            }
            if( volumeId == null ) {
                return null;
            }

            String region = getContext().getRegionId();
            /*String dataCenter;
            if( json.has("availability_zone") && json.getString("availability_zone") != null && !json.getString("availability_zone").isEmpty() ) {
                dataCenter = json.getString("availability_zone");
            }
            else {
                dataCenter = region + "-a";
            }*/
            String dataCenter = region + "-a";

            String name = (json.has("displayName") ? json.getString("displayName") : null);

            if( name == null ) {
                name = (json.has("display_name") ? json.getString("display_name") : null);
                if( name == null ) {
                    name = volumeId;
                }
            }

            String description = (json.has("displayDescription") ? json.getString("displayDescription") : null);

            if( description == null ) {
                description = (json.has("display_description") ? json.getString("display_description") : null);
                if( description == null ) {
                    description = name;
                }
            }

            long created = (json.has("createdAt") ? (getProvider()).parseTimestamp(json.getString("createdAt")) : -1L);

            if( created < 1L ) {
                created = (json.has("created_at") ? (getProvider()).parseTimestamp(json.getString("created_at")) : -1L);
            }

            int size = 0;

            if( json.has("size") ) {
                size = json.getInt("size");
            }
            String productId = null;

            if( json.has("volume_type") ) {
                productId = json.getString("volume_type");
            }
            else if( json.has("volumeType") ) {
                productId = json.getString("volumeType");
            }
            String vmId = null, deviceId = null;

            if( json.has("attachments") ) {
                JSONArray servers = json.getJSONArray("attachments");

                for( int i=0; i<servers.length(); i++ ) {
                    JSONObject ob = servers.getJSONObject(i);

                    if( ob.has("serverId") ) {
                        vmId = ob.getString("serverId");
                    }
                    else if( ob.has("server_id") ) {
                        vmId = ob.getString("server_id");
                    }
                    if( ob.has("device") ) {
                        deviceId = ob.getString("device");
                    }
                    if( vmId != null ) {
                        break;
                    }
                }
            }
            String snapshotId = (json.has("snapshotId") ? json.getString("snapshotId") : null);

            if( snapshotId == null ) {
                snapshotId = (json.has("snapshot_id") ? json.getString("snapshot_id") : null);
            }

            VolumeState currentState = VolumeState.PENDING;

            if( json.has("status") ) {
                String status = json.getString("status");

                if( status.equalsIgnoreCase("available") ) {
                    currentState = VolumeState.AVAILABLE;
                }
                else if( status.equalsIgnoreCase("creating") ) {
                    currentState = VolumeState.PENDING;
                }
                else if( status.equalsIgnoreCase("error") ) {
                    currentState = VolumeState.ERROR;
                }
                else if( status.equals("in-use") ) {
                    currentState = VolumeState.AVAILABLE;
                }
                else if( status.equals("attaching") ) {
                    currentState = VolumeState.PENDING;
                }
                else {
                    logger.warn("DEBUG: Unknown OpenStack Cinder volume state: " + status);
                }
            }
            Volume volume = new Volume();

            volume.setCreationTimestamp(created);
            volume.setCurrentState(currentState);
            volume.setDeviceId(deviceId);
            volume.setName(name);
            volume.setDescription(description);
            volume.setProviderDataCenterId(dataCenter);
            volume.setProviderRegionId(region);
            volume.setProviderSnapshotId(snapshotId);
            volume.setProviderVirtualMachineId(vmId);
            volume.setProviderVolumeId(volumeId);
            volume.setSize(new Storage<>(size, Storage.GIGABYTE));
            if( productId != null ) {
                VolumeProduct match = null;

                for( VolumeProduct prd : types ) {
                    if( productId.equals(prd.getProviderProductId()) ) {
                        match = prd;
                        break;
                    }
                }
                if( match == null ) { // TODO: stupid Folsom bug
                    for( VolumeProduct prd : types ) {
                        if( productId.equals(prd.getName()) ) {
                            match = prd;
                            break;
                        }
                    }
                }
                if( match != null ) {
                    volume.setProviderProductId(match.getProviderProductId());
                    volume.setType(match.getType());
                }
            }
            if( volume.getProviderProductId() == null ) {
                volume.setProviderProductId(productId);
            }
            if( volume.getType() == null ) {
                volume.setType(VolumeType.HDD);
            }
            return volume;
        }
        catch( JSONException e ) {
            throw new CommunicationException("Unable to understand toVolume response: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void setTags(@Nonnull String volumeId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.setTags");
    	try {
    		getProvider().createTags( SERVICE, "/volumes", volumeId, tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void setTags(@Nonnull String[] volumeIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	for( String id : volumeIds ) {
    		setTags(id, tags);
    	}
    }
    
    @Override
    public void updateTags(@Nonnull String volumeId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.updateTags");
    	try {
    		getProvider().updateTags( SERVICE, "/volumes", volumeId, tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void updateTags(@Nonnull String[] volumeIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	for( String id : volumeIds ) {
    		updateTags(id, tags);
    	}
    }
    
    @Override
    public void removeTags(@Nonnull String volumeId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.removeTags");
    	try {
    		getProvider().removeTags( SERVICE, "/volumes", volumeId, tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void removeTags(@Nonnull String[] volumeIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	for( String id : volumeIds ) {
    		removeTags(id, tags);
    	}
    }
}
