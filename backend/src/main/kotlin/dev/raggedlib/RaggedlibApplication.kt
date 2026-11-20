package dev.raggedlib

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(RaggedlibProperties::class)
@EnableScheduling // pending-upload cleanup, purging soft-deleted books
class RaggedlibApplication

fun main(args: Array<String>) {
    runApplication<RaggedlibApplication>(*args)
}
