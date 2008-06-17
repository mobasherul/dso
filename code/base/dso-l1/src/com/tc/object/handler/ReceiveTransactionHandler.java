/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.Sink;
import com.tc.exception.TCClassNotFoundException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.protocol.tcm.ChannelIDProvider;
import com.tc.object.ClientConfigurationContext;
import com.tc.object.dmi.DmiDescriptor;
import com.tc.object.event.DmiEventContext;
import com.tc.object.event.DmiManager;
import com.tc.object.gtx.ClientGlobalTransactionManager;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.lockmanager.api.ClientLockManager;
import com.tc.object.lockmanager.api.LockContext;
import com.tc.object.msg.AcknowledgeTransactionMessage;
import com.tc.object.msg.BroadcastTransactionMessageImpl;
import com.tc.object.msg.DSOMessageBase;
import com.tc.object.msg.L1AcknowledgeTransactionMessageBatchManager;
import com.tc.object.session.SessionManager;
import com.tc.object.tx.ClientTransactionManager;
import com.tc.util.Assert;
import com.tcclient.object.DistributedMethodCall;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author steve
 */
public class ReceiveTransactionHandler extends AbstractEventHandler {
  private static final TCLogger                             logger = TCLogging
                                                                       .getLogger(ReceiveTransactionHandler.class);

  private ClientTransactionManager                          txManager;
  private ClientLockManager                                 lockManager;
  private final SessionManager                              sessionManager;
  private final ClientGlobalTransactionManager              gtxManager;
  private final ChannelIDProvider                           cidProvider;
  private final Sink                                        dmiSink;
  private final DmiManager                                  dmiManager;
  private final L1AcknowledgeTransactionMessageBatchManager acknowledgeTransactionBatchManager;

  public ReceiveTransactionHandler(ChannelIDProvider provider, ClientGlobalTransactionManager gtxManager,
                                   SessionManager sessionManager, Sink dmiSink, DmiManager dmiManager,
                                   L1AcknowledgeTransactionMessageBatchManager acknowledgeTransactionBatchManager) {
    this.cidProvider = provider;
    this.gtxManager = gtxManager;
    this.sessionManager = sessionManager;
    this.dmiSink = dmiSink;
    this.dmiManager = dmiManager;
    this.acknowledgeTransactionBatchManager = acknowledgeTransactionBatchManager;
  }

  public void handleEvent(EventContext context) {
    final BroadcastTransactionMessageImpl btm = (BroadcastTransactionMessageImpl) context;

    if (false) System.err.println(cidProvider.getChannelID() + ": ReceiveTransactionHandler: committer="
                                            + btm.getCommitterID() + ", " + btm.getTransactionID()
                                            + btm.getGlobalTransactionID() + ", notified: "
                                            + btm.addNotifiesTo(new LinkedList()) + ", lookup ObjectIDs: "
                                            + btm.getLookupObjectIDs());

    Assert.eval(btm.getLockIDs().size() > 0);
    GlobalTransactionID lowWaterMark = btm.getLowGlobalTransactionIDWatermark();
    if (!lowWaterMark.isNull()) {
      gtxManager.setLowWatermark(lowWaterMark);
    }
    if (gtxManager.startApply(btm.getCommitterID(), btm.getTransactionID(), btm.getGlobalTransactionID())) {
      Collection changes = btm.getObjectChanges();
      if (changes.size() > 0 || btm.getLookupObjectIDs().size() > 0 || btm.getNewRoots().size() > 0) {

        if (false) System.err.println(cidProvider.getChannelID() + " Applying - committer=" + btm.getCommitterID()
                                      + " , " + btm.getTransactionID() + " , " + btm.getGlobalTransactionID());

        try {
          txManager.apply(btm.getTransactionType(), btm.getLockIDs(), changes, btm.getLookupObjectIDs(), btm
              .getNewRoots());
        } catch (TCClassNotFoundException cnfe) {
          logger.warn("transaction apply failed for " + btm.getTransactionID(), cnfe);
        }

      }
    }

    Collection notifies = btm.addNotifiesTo(new LinkedList());
    for (Iterator i = notifies.iterator(); i.hasNext();) {
      LockContext lc = (LockContext) i.next();
      lockManager.notified(lc.getLockID(), lc.getThreadID());
    }

    List dmis = btm.getDmiDescriptors();
    for (Iterator i = dmis.iterator(); i.hasNext();) {
      DmiDescriptor dd = (DmiDescriptor) i.next();

      // NOTE: This prepare call must happen before handing off the DMI to the stage, and more
      // importantly before sending ACK below
      DistributedMethodCall dmc = dmiManager.extract(dd);
      if (dmc != null) {
        dmiSink.add(new DmiEventContext(dmc));
      }
    }

    // XXX:: This is a potential race condition here 'coz after we decide to send an ACK
    // and before we actually send it, the server may go down and come back up !
    if (sessionManager.isCurrentSession(btm.getLocalSessionID())) {
      AcknowledgeTransactionMessage ack = acknowledgeTransactionBatchManager.createMessage();
      ack.initialize();
      ack.addAckMessage(btm.getCommitterID(), btm.getTransactionID());
      // ack.send()
      acknowledgeTransactionBatchManager.sendBatch((DSOMessageBase)ack);
    }
    btm.recycle();
  }

  public void initialize(ConfigurationContext context) {
    super.initialize(context);
    ClientConfigurationContext ccc = (ClientConfigurationContext) context;
    this.txManager = ccc.getTransactionManager();
    this.lockManager = ccc.getLockManager();
  }

}