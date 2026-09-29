package com.gettyio.gim.agent.domain.persist;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 行映射器：将当前游标行转为实体
 */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet rs) throws SQLException;
}
