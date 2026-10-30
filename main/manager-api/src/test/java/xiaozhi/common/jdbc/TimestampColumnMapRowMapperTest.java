package xiaozhi.common.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TimestampColumnMapRowMapperTest {
    private final TimestampColumnMapRowMapper mapper = new TimestampColumnMapRowMapper();

    @Test void datetimeUsesDriverConversionInsteadOfGuessingUtc() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(LocalDateTime.parse("2026-10-30T19:29:00"));
        var actual = Timestamp.from(Instant.parse("2026-10-30T11:29:00Z"));
        when(rs.getTimestamp(1)).thenReturn(actual);
        assertSame(actual, mapper.getColumnValue(rs, 1));
    }

    @Test void timestampsNullsAndLongIdentifiersRetainTheirValues() throws Exception {
        var rs = mock(ResultSet.class);
        var timestamp = Timestamp.from(Instant.parse("2026-10-30T11:29:00Z"));
        when(rs.getObject(1)).thenReturn(timestamp);
        when(rs.getObject(2)).thenReturn(null);
        when(rs.getObject(3)).thenReturn(2116127778922262529L);
        assertSame(timestamp, mapper.getColumnValue(rs, 1));
        assertNull(mapper.getColumnValue(rs, 2));
        assertEquals(2116127778922262529L, mapper.getColumnValue(rs, 3));
        verify(rs, never()).getTimestamp(anyInt());
    }

    @Test void missingDriverConversionDoesNotGuessAnOffset() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(LocalDateTime.parse("2026-10-30T19:29:00"));
        assertThrows(SQLException.class, () -> mapper.getColumnValue(rs, 1));
    }
}
