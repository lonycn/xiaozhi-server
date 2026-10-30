package xiaozhi.common.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.ColumnMapRowMapper;

/** Read MySQL DATETIME through the driver's configured connection time zone. */
public final class TimestampColumnMapRowMapper extends ColumnMapRowMapper {
    @Override
    protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
        Object value = super.getColumnValue(rs, index);
        // Connector/J returns LocalDateTime from getObject(DATETIME). Treating
        // that value as UTC loses the actual JDBC connection time zone.
        if (value instanceof LocalDateTime) {
            var timestamp = rs.getTimestamp(index);
            if (timestamp == null) throw new SQLException("Cannot resolve timestamp using connection time zone");
            return timestamp;
        }
        return value;
    }
}
