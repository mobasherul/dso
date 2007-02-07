/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.dmi;

import com.tc.async.api.EventContext;
import com.tc.io.TCByteBufferInputStream;
import com.tc.io.TCByteBufferOutput;
import com.tc.io.TCSerializable;
import com.tc.object.ObjectID;
import com.tc.util.Assert;

import java.io.IOException;

/**
 * Representation of a distributed method invocation
 */
public class DmiDescriptor implements TCSerializable, EventContext {

  private ObjectID receiverId;
  private ObjectID dmiCallId;

  public DmiDescriptor() {
    receiverId = null;
    dmiCallId = null;
  }

  public DmiDescriptor(ObjectID receiverId, ObjectID dmiCallId) {
    Assert.pre(receiverId != null);
    Assert.pre(dmiCallId != null);

    this.receiverId = receiverId;
    this.dmiCallId = dmiCallId;
  }

  public ObjectID getReceiverId() {
    return receiverId;
  }

  public ObjectID getDmiCallId() {
    return dmiCallId;
  }

  public Object deserializeFrom(TCByteBufferInputStream serialInput) throws IOException {
    receiverId = new ObjectID(serialInput.readLong());
    dmiCallId = new ObjectID(serialInput.readLong());
    return this;
  }

  public void serializeTo(TCByteBufferOutput serialOutput) {
    serialOutput.writeLong(receiverId.toLong());
    serialOutput.writeLong(dmiCallId.toLong());
  }
}
