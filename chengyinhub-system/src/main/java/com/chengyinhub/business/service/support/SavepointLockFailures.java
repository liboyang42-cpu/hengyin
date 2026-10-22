package com.chengyinhub.business.service.support;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.TransactionSystemException;

/**
 * NESTED savepoint 外面那层「失败只回滚到 savepoint、主流程照常」的 catch,不能吞掉锁冲突。
 *
 * <p>MySQL 1213(死锁)会让 InnoDB 回滚整笔物理事务,savepoint 随之失效。Spring 再去回滚到 savepoint 时
 * 得到 {@code SAVEPOINT ... does not exist},包成 {@link TransactionSystemException}(原异常挂在
 * {@link TransactionSystemException#getApplicationException()} 上),外层事务也不会被标成 rollback-only。
 * 这时 catch 一吞,外层 {@code @DeadlockRetry} 就看不到锁冲突、不会重试,后续语句落进 InnoDB 隐式开启的新事务并被提交
 * = 死锁前那部分写入丢了,接口却返回成功。
 *
 * <p>这里只认 {@code DeadlockRetryInterceptor} 会重试的两种:{@link DeadlockLoserDataAccessException}(1213 / SQLState 40001)
 * 与 {@link CannotAcquireLockException}(1205)。找到就原样抛出,让外层整笔回滚并重试;其它异常一概不管,由调用方照旧处理。
 */
public final class SavepointLockFailures
{
    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    private SavepointLockFailures()
    {
    }

    /** 失败链里有锁冲突就抛出它(未经 Spring 翻译的 1213/40001/1205 先翻译成对应异常);否则什么都不做。 */
    public static void rethrowIfLockFailure(Throwable failure)
    {
        RuntimeException lockFailure = find(failure);
        if (lockFailure != null)
        {
            throw lockFailure;
        }
    }

    @SuppressWarnings("deprecation")
    static RuntimeException find(Throwable failure)
    {
        if (failure == null)
        {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty())
        {
            Throwable current = pending.poll();
            if (!seen.add(current))
            {
                continue;
            }
            if (current instanceof DeadlockLoserDataAccessException || current instanceof CannotAcquireLockException)
            {
                return (RuntimeException) current;
            }
            if (current instanceof SQLException)
            {
                SQLException sql = (SQLException) current;
                if (sql.getErrorCode() == MYSQL_DEADLOCK || SQLSTATE_SERIALIZATION_FAILURE.equals(sql.getSQLState()))
                {
                    return new DeadlockLoserDataAccessException(sql.getMessage(), sql);
                }
                if (sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT)
                {
                    return new CannotAcquireLockException(sql.getMessage(), sql);
                }
            }
            if (current instanceof TransactionSystemException
                    && ((TransactionSystemException) current).getApplicationException() != null)
            {
                pending.add(((TransactionSystemException) current).getApplicationException());
            }
            if (current.getCause() != null)
            {
                pending.add(current.getCause());
            }
        }
        return null;
    }
}
