/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.EventHandler;
import com.tc.async.api.Sink;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ClientID;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.object.ObjectID;
import com.tc.object.ObjectRequestID;
import com.tc.object.msg.RespondToKeyValueMappingRequestMessage;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.net.NoSuchChannelException;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.context.EntryForKeyResponseContext;
import com.tc.objectserver.context.ObjectRequestServerContextImpl;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.managedobject.ConcurrentDistributedMapManagedObjectState;
import com.tc.util.ObjectIDSet;

public class RespondToPartialKeysRequestHandler extends AbstractEventHandler implements EventHandler {

  private final static TCLogger logger = TCLogging.getLogger(RespondToPartialKeysRequestHandler.class);
  private DSOChannelManager     channelManager;
  private ObjectManager         objectManager;
  private Sink                  managedObjectRequestSink;

  @Override
  public void handleEvent(final EventContext context) {
    EntryForKeyResponseContext responseContext = (EntryForKeyResponseContext) context;

    ObjectID mapID = responseContext.getMapID();
    Object portableKey = responseContext.getPortableKey();

    ManagedObject mo = responseContext.getManagedObject();
    ManagedObjectState state = mo.getManagedObjectState();

    if (!(state instanceof ConcurrentDistributedMapManagedObjectState)) { throw new AssertionError(
                                                                                              " Map "
                                                                                                  + mapID
                                                                                                  + " is not a ConcurrentStringMap ManagedObjectState."); }

    ConcurrentDistributedMapManagedObjectState csmState = (ConcurrentDistributedMapManagedObjectState) state;

    Object portableValue = csmState.getValueForKey(portableKey);
    // System.err.println("Server : Send response for partial key lookup : " + responseContext + " value : "
    // + portableValue);

    this.objectManager.releaseReadOnly(mo);

    ClientID clientID = responseContext.getClientID();
    preFetchPortableValueIfNeeded(portableValue, clientID);

    MessageChannel channel;
    try {
      channel = this.channelManager.getActiveChannel(clientID);
    } catch (NoSuchChannelException e) {
      logger.warn("Client " + responseContext.getClientID() + " disconnect before sending Entry for mapID : " + mapID
                  + " key : " + portableKey);
      return;
    }

    RespondToKeyValueMappingRequestMessage responseMessage = (RespondToKeyValueMappingRequestMessage) channel
        .createMessage(TCMessageType.KEY_VALUE_MAPPING_RESPONSE_MESSAGE);
    responseMessage.initialize(mapID, portableKey, portableValue);
    responseMessage.send();

  }

  private void preFetchPortableValueIfNeeded(final Object portableValue, final ClientID clientID) {
    if (portableValue instanceof ObjectID) {
      ObjectID valueID = (ObjectID) portableValue;
      ObjectIDSet lookupIDs = new ObjectIDSet();
      lookupIDs.add(valueID);
      this.managedObjectRequestSink.add(new ObjectRequestServerContextImpl(clientID, ObjectRequestID.NULL_ID,
                                                                           lookupIDs, Thread.currentThread().getName(),
                                                                           -1, true, true));
    }
  }

  @Override
  protected void initialize(final ConfigurationContext context) {
    ServerConfigurationContext scc = (ServerConfigurationContext) context;
    this.channelManager = scc.getChannelManager();
    this.objectManager = scc.getObjectManager();
    this.managedObjectRequestSink = scc.getStage(ServerConfigurationContext.MANAGED_OBJECT_REQUEST_STAGE).getSink();
  }

}
