package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Dormant source adapter: explicitly constructed only in isolated evaluation.
 * Original IM SQL and W16 writes share the same verified session/data source.
 * The global W16 guard precedes the block INSERT, so accept/propose cannot race
 * between authoritative block persistence and bilateral consent termination.
 */
public final class RendezvousImBlockMutation implements ImBlockMutation {
    private final MyBatisRendezvousConsentStore store;
    private final ImBlockMapper blocks;

    public RendezvousImBlockMutation(MyBatisRendezvousConsentStore.Mode mode,
            SqlSessionTemplate session, DataSourceTransactionManager manager) {
        store = new MyBatisRendezvousConsentStore(mode, session, manager);
        blocks = session.getMapper(ImBlockMapper.class);
    }

    public void insert(ImBlock block) {
        Objects.requireNonNull(block);
        Long first = block.getMemberId(), second = block.getBlockedMemberId();
        if (first == null || second == null || first.equals(second)) {
            throw new IllegalArgumentException("W16_INVALID_BLOCK");
        }
        ImBlock persisted = new ImBlock();
        persisted.setMemberId(first); persisted.setBlockedMemberId(second);
        store.transaction(tx -> {
            // Keep existing duplicate-key update semantics, including a zero
            // changed-row count. Read persisted truth instead of trusting inputs.
            blocks.insertImBlock(persisted);
            if (blocks.selectOne(first, second) == null || blocks.existsBetween(first, second) <= 0) {
                throw new IllegalStateException("W16_BLOCK_NOT_PERSISTED");
            }
            RendezvousConsentService.stopBlockedPair(tx, first, second);
            return null;
        });
    }
}
