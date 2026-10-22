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
 * <p>只在 savepoint 确已失效时抛出:死锁({@link DeadlockLoserDataAccessException} / 1213 / SQLState 40001,
 * 含 Spring 把 40001 翻译成的 {@link CannotAcquireLockException}),或「回滚到 savepoint 失败」的
 * {@link TransactionSystemException} 里挂着的任何锁失败。找到就抛出,让外层整笔回滚并由 {@code @DeadlockRetry} 重试。
 *
 * <p>1205 锁等待超时在 {@code innodb_rollback_on_timeout=OFF}(默认)下只回滚当前语句,savepoint 仍有效,
 * 照旧由调用方吞掉、回滚到 savepoint 即可(多数调用方外层没有 {@code @DeadlockRetry},抛出会让整单失败);
 * 只有它导致 savepoint 失效(回滚到 savepoint 失败)时才抛出。其它异常一概不管,由调用方照旧处理。
 */
public final class SavepointLockFailures
{
    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    private SavepointLockFailures()
    {
    }

    /** savepoint 已因锁冲突失效就抛出该锁失败(未经 Spring 翻译的 1213/40001 先翻译);否则什么都不做。 */
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
        // 回滚到 savepoint 失败:savepoint 已不存在,其中挂着的锁失败(含 1205)都必须交外层整笔重试。
        boolean savepointGone = false;
        while (!pending.isEmpty())
        {
            Throwable current = pending.poll();
            if (!seen.add(current))
            {
                continue;
            }
            if (current instanceof DeadlockLoserDataAccessException)
            {
                return (RuntimeException) current;
            }
            if (current instanceof CannotAcquireLockException && (savepointGone || causedByDeadlock(current)))
            {
                return (RuntimeException) current;
            }
            if (current instanceof SQLException)
            {
                SQLException sql = (SQLException) current;
                if (isDeadlock(sql))
                {
                    return new DeadlockLoserDataAccessException(sql.getMessage(), sql);
                }
                if (savepointGone && sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT)
                {
                    return new CannotAcquireLockException(sql.getMessage(), sql);
                }
            }
            if (current instanceof TransactionSystemException
                    && ((TransactionSystemException) current).getApplicationException() != null)
            {
                savepointGone = true;
                pending.add(((TransactionSystemException) current).getApplicationException());
            }
            if (current.getCause() != null)
            {
                pending.add(current.getCause());
            }
        }
        return null;
    }

    private static boolean isDeadlock(SQLException sql)
    {
        return sql.getErrorCode() == MYSQL_DEADLOCK || SQLSTATE_SERIALIZATION_FAILURE.equals(sql.getSQLState());
    }

    /** Spring 6 会把 SQLState 40001 的回滚异常翻译成 CannotAcquireLockException,这里看底层 SQLException 区分死锁与 1205。 */
    private static boolean causedByDeadlock(Throwable lockFailure)
    {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = lockFailure.getCause(); t != null && seen.add(t); t = t.getCause())
        {
            if (t instanceof SQLException && isDeadlock((SQLException) t))
            {
                return true;
            }
        }
        return false;
    }
}
