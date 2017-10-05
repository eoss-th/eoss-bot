package com.eoss.bot.line;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;

@Data
@Validated
@ConfigurationProperties(prefix = "eoss.bot")
public class EOSSBotProperties {

    @Valid
    private String name;

    @Valid
    private String domain;

    @Valid
    private String lineId;

}
