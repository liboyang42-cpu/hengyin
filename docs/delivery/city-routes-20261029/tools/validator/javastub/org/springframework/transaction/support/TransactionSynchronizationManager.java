package org.springframework.transaction.support;
public class TransactionSynchronizationManager { public static boolean isActualTransactionActive(){return false;} public static boolean isCurrentTransactionReadOnly(){return false;} }
