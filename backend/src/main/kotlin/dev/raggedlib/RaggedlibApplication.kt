package dev.raggedlib

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(RaggedlibProperties::class)
class RaggedlibApplication

fun main(args: Array<String>) {
    runApplication<RaggedlibApplication>(*args)
}
