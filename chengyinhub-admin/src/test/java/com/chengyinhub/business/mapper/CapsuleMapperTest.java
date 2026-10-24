package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.CapsuleRecord;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
/** Actual MyBatis owner/keyset/redaction SQL against an isolated H2 fixture; VPS only. */
class CapsuleMapperTest {
    private SqlSessionFactory factory() throws Exception {
        UnpooledDataSource source=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:capsule_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        try(Connection c=source.getConnection();Statement s=c.createStatement()) {
            s.execute("create table member_time_capsule(id bigint auto_increment primary key,member_id bigint not null,request_id varchar(64) not null,payload_hash char(64) not null,body text,photo_key varchar(512),unlock_at_ms bigint not null,created_at_ms bigint not null,deleted tinyint not null,unique(member_id,request_id))");
        }
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        String resource="mapper/business/CapsuleMapper.xml";
        try(InputStream in=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();}
        return new SqlSessionFactoryBuilder().build(config);
    }
    private CapsuleRecord row(Long owner,String key){CapsuleRecord r=new CapsuleRecord();r.setMemberId(owner);r.setRequestId(key);r.setPayloadHash(String.join("",java.util.Collections.nCopies(64,"a")));r.setBody("secret");r.setPhotoKey("private/key");r.setUnlockAtMs(10L);r.setCreatedAtMs(1L);return r;}
    @Test void ownerIsolationMetadataListAndDeletionTombstone() throws Exception {
        try(SqlSession session=factory().openSession(true)) {
            CapsuleMapper mapper=session.getMapper(CapsuleMapper.class);CapsuleRecord r=row(7L,"request-one");assertEquals(1,mapper.insert(r));
            assertNull(mapper.byId(8L,r.getId()));assertNull(mapper.byRequest(8L,"request-one"));assertEquals(0,mapper.delete(8L,r.getId()));
            CapsuleRecord list=mapper.page(7L,null,20).get(0);assertNull(list.getBody());assertNull(list.getPhotoKey());assertEquals("secret",mapper.byId(7L,r.getId()).getBody());
            assertEquals(1,mapper.delete(7L,r.getId()));assertEquals(0,mapper.delete(7L,r.getId()));assertNull(mapper.byId(7L,r.getId()));assertTrue(mapper.page(7L,null,20).isEmpty());
            CapsuleRecord tombstone=mapper.byRequest(7L,"request-one");assertEquals(1,tombstone.getDeleted());assertNull(tombstone.getBody());assertNull(tombstone.getPhotoKey());assertNotNull(tombstone.getPayloadHash());
        }
    }
    @Test void keysetPageDoesNotRepeatAndUniqueKeyCannotCreateSecondCopy() throws Exception {
        try(SqlSession session=factory().openSession(true)) {
            CapsuleMapper mapper=session.getMapper(CapsuleMapper.class);CapsuleRecord first=row(7L,"one"),second=row(7L,"two");mapper.insert(first);mapper.insert(second);
            assertEquals(second.getId(),mapper.page(7L,null,1).get(0).getId());assertEquals(first.getId(),mapper.page(7L,second.getId(),1).get(0).getId());
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->mapper.insert(row(7L,"one")));
            assertEquals(1,mapper.insert(row(8L,"one")));
        }
    }
}
