package com.react.chat;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://${TEST_MYSQL_HOST:localhost}:${TEST_MYSQL_PORT:3306}/react_test?connectionTimeZone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.datasource.hikari.connection-init-sql=SET SESSION time_zone = '+09:00'"})
@ActiveProfiles("test")
class ChatMessageTimeZoneSeoulConnectionTest extends ChatMessageTimeZoneTest {
    @Override
    String connectionOffset() {
        return "+09:00";
    }
}
