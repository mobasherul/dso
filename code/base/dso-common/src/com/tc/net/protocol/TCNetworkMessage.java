/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.net.protocol;

import com.tc.async.api.EventContext;
import com.tc.bytes.ITCByteBuffer;
import com.tc.lang.Recyclable;

/**
 * @author teck
 */
public interface TCNetworkMessage extends EventContext, Recyclable {

  public TCNetworkHeader getHeader();

  public TCNetworkMessage getMessagePayload();

  public ITCByteBuffer[] getPayload();

  public ITCByteBuffer[] getEntireMessageData();

  public boolean isSealed();

  public void seal();

  public int getDataLength();

  public int getHeaderLength();

  public int getTotalLength();

  public void wasSent();

  public void setSentCallback(Runnable callback);
  
  public Runnable getSentCallback();
}