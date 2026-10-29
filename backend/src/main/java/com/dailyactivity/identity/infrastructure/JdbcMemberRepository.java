package com.dailyactivity.identity.infrastructure;

import com.dailyactivity.auth.domain.Actor;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.domain.port.MemberRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcMemberRepository implements MemberRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMemberRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String select() {
        return "SELECT id,account,name,active,updated_at FROM seller WHERE merchant_id=:merchantId AND deleted=0";
    }

    @Override
    public List<Member> page(long merchantId, String q, int page) {
        var rows = jdbc.query(select() + (q == null || q.isBlank() ? "" : " AND (account LIKE :query OR name LIKE :query)") + " ORDER BY id LIMIT 10 OFFSET :offset", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("query", q == null ? null : "%" + q.trim() + "%").addValue("offset", (page - 1) * 10), (rs, row) -> new Member(rs.getLong("id"), rs.getString("account"), rs.getString("name"), rs.getBoolean("active"), List.of(), rs.getTimestamp("updated_at").toInstant()));
        return rows.stream().map(m -> withPermissions(m)).toList();
    }

    @Override
    public long count(long merchantId, String q) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seller WHERE merchant_id=:merchantId AND deleted=0" + (q == null || q.isBlank() ? "" : " AND (account LIKE :query OR name LIKE :query)"), new MapSqlParameterSource("query", q == null ? null : "%" + q.trim() + "%").addValue("merchantId", merchantId), Long.class);
    }

    @Override
    public long create(long merchantId, MemberInput i, Actor actor) {
        try {
            KeyHolder h = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO seller(merchant_id,account,name,password_hash,active,created_by_user_id,created_by_type,updated_by_user_id,updated_by_type) VALUES(:merchantId,:account,:name,:hash,1,:actor,:actorType,:actor,:actorType)", params(i, actor).addValue("merchantId", merchantId), h, new String[]{"id"});
            long id = h.getKey().longValue();
            replacePermissions(id, i.permissions(), actor);
            return id;
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("MEMBER_ACCOUNT_EXISTS", "Member account is already in use.");
        }
    }

    @Override
    public void update(long merchantId, long id, MemberInput i, Actor actor) {
        try {
            int n = jdbc.update("UPDATE seller SET account=:account,name=:name,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type=:actorType WHERE merchant_id=:merchantId AND id=:id AND deleted=0", params(i, actor).addValue("merchantId", merchantId).addValue("id", id));
            if (n != 1) throw ApiException.notFound("Member not found.");
            if (i.passwordHash() != null)
                jdbc.update("UPDATE seller SET password_hash=:hash,auth_version=auth_version+1 WHERE id=:id", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("hash", i.passwordHash()));
            replacePermissions(id, i.permissions(), actor);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("MEMBER_ACCOUNT_EXISTS", "Member account is already in use.");
        }
    }

    @Override
    public void setActive(long merchantId, long id, boolean active, Actor actor) {
        int n = jdbc.update("UPDATE seller SET active=:active,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type=:actorType WHERE merchant_id=:merchantId AND id=:id AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("active", active).addValue("actor", actor.id()).addValue("actorType", actor.type()));
        if (n != 1) throw ApiException.notFound("Member not found.");
    }

    @Override
    public Optional<Member> find(long merchantId, long id) {
        return jdbc.query(select() + " AND id=:id", new MapSqlParameterSource("id", id).addValue("merchantId", merchantId), rows -> rows.next() ? Optional.of(withPermissions(new Member(rows.getLong("id"), rows.getString("account"), rows.getString("name"), rows.getBoolean("active"), List.of(), rows.getTimestamp("updated_at").toInstant()))) : Optional.empty());
    }

    @Override
    public long activeMemberManagers(long merchantId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM seller u JOIN seller_permission p ON p.seller_id=u.id AND p.deleted=0 AND p.menu_code='members' WHERE u.merchant_id=:merchantId AND u.active=1 AND u.deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId), Long.class);
        return total == null ? 0 : total;
    }

    private Member withPermissions(Member m) {
        return new Member(m.id(), m.account(), m.name(), m.active(), jdbc.query("SELECT menu_code FROM seller_permission WHERE seller_id=:id AND deleted=0 ORDER BY menu_code", new MapSqlParameterSource("id", m.id()), (rs, row) -> rs.getString(1)), m.updatedAt());
    }

    private void replacePermissions(long id, List<String> permissions, Actor actor) {
        jdbc.update("UPDATE seller_permission SET deleted=id,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type=:actorType WHERE seller_id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("actor", actor.id()).addValue("actorType", actor.type()));
        if (permissions == null) return;
        for (String code : permissions) {
            Long exists = jdbc.query("SELECT id FROM permission_menu WHERE menu_code=:code", new MapSqlParameterSource("code", code), rows -> rows.next() ? rows.getLong(1) : null);
            if (exists == null)
                throw ApiException.badRequest("INVALID_PERMISSION", "Unknown member permission: " + code);
            jdbc.update("INSERT INTO seller_permission(seller_id,menu_code,created_by_user_id,created_by_type,updated_by_user_id,updated_by_type) VALUES(:id,:code,:actor,:actorType,:actor,:actorType)", new MapSqlParameterSource().addValue("id", id).addValue("code", code).addValue("actor", actor.id()).addValue("actorType", actor.type()));
        }
    }

    private MapSqlParameterSource params(MemberInput i, Actor actor) {
        return new MapSqlParameterSource().addValue("account", i.account()).addValue("name", i.name()).addValue("hash", i.passwordHash()).addValue("actor", actor.id()).addValue("actorType", actor.type());
    }
}
