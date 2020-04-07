package test.throttle;

import me.insidezhou.southernquiet.throttle.Throttle;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.UUID;

@SpringBootTest
@RunWith(SpringRunner.class)
public class RedisThrottleTest extends ThrottleTest {
    @Configuration
    @EnableAutoConfiguration
    @ComponentScan({"me.insidezhou.southernquiet.throttle"})
    public static class Config {}

}
