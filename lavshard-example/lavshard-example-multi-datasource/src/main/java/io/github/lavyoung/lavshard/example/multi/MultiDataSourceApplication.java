package io.github.lavyoung.lavshard.example.multi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 *
 * LavShard 多数据源分库示例启动入口。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
@SpringBootApplication
public class MultiDataSourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                MultiDataSourceApplication.class,
                args
        );
    }
}
