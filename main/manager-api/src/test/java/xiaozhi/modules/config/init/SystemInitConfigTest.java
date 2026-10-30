package xiaozhi.modules.config.init;

import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.sys.service.SysParamsService;

class SystemInitConfigTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"old-version",Constant.VERSION})
    void startupPreservesRedisDataRegardlessOfVersionMarker(String previous) {
        var redis=mock(RedisUtils.class);
        var params=mock(SysParamsService.class);
        var config=mock(ConfigService.class);
        var devices=mock(DeviceAddressBookService.class);
        var startup=new SystemInitConfig();
        ReflectionTestUtils.setField(startup,"redisUtils",redis);
        ReflectionTestUtils.setField(startup,"sysParamsService",params);
        ReflectionTestUtils.setField(startup,"configService",config);
        ReflectionTestUtils.setField(startup,"deviceAddressBookService",devices);
        when(redis.get(RedisKeys.getVersionKey())).thenReturn(previous);

        startup.init();

        verify(redis).get(RedisKeys.getVersionKey());
        if(!Constant.VERSION.equals(previous)) verify(redis).set(RedisKeys.getVersionKey(),Constant.VERSION);
        verifyNoMoreInteractions(redis);
        verify(params).initServerSecret();
        verify(config).getConfig(false);
        verify(devices).refreshCache();
    }
}
