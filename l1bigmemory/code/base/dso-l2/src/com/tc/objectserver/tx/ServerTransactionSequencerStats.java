/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.tx;

public interface ServerTransactionSequencerStats {
  
  public int getTxnsCount();
  
  public int getPendingTxnsCount();
  
  public int getBlockedTxnsCount();
  
  public int getBlockedObjectsCount();
  
}
