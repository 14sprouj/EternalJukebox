package org.abimon.eternalJukebox

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.Router
import org.abimon.visi.security.sha256Hash
import java.io.File
import java.net.InetAddress
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

val JSON_CONFIG_FILE = File("new_config.json")
val YAML_CONFIG_FILE = File("new_config.yaml")

fun main(args: Array<String>) {
    println("==Eternal Jukebox==")
    println("Attempting to load config...")

    var config = loadConfig()

    if (config == null) {
        println("Error: No config file detected")
        print("Would you like to initialise a configuration (Y/n)? ")

        if(readFirstCharacter() != 'y') {
            println("No config file detected, and no configuration will be initialised.")
            println("Terminating...")

            System.exit(ProgramReturnCodes.NO_CONFIG_DETECTED)

            return //Safety net
        }

        println("Initialising a configuration...")
        print("Would you like to use a web browser (Y/n)? ")

        if (readFirstCharacter() == 'y') {
            val vertx = Vertx.vertx()
            val web = vertx.createHttpServer()
            val router = Router.router(vertx)
            val waiting = AtomicInteger(0)

            val rng: Random = SecureRandom()

            val token = ByteArray(8192).also(rng::nextBytes).sha256Hash()

            router.get("/configure").handler { context ->
                if (context.queryParam("token").none { str -> str == token }) {
                    context.fail(401)
                    return@handler
                }

                vertx.executeBlocking<ByteArray>({ future ->
                    val stream = EternalJukebox::class.java.classLoader.getResourceAsStream("configure.html") ?: return@executeBlocking future.fail("No resource for name 'configure.html'")
                    future.complete(stream.use { dispStream -> dispStream.readBytes() })
                }, { res ->
                    val response = context.response()
                    if (res.succeeded()) {
                        response.putHeader("Content-Type", "text/html;charset=UTF-8")
                        response.end(Buffer.buffer(res.result()))
                    } else {
                        response.putHeader("Content-Type", "application/json")
                        response.end(jsonStringOfObject("error_code" to WebErrorCodes.NO_CONFIGURE_FILE, "error_message" to WebErrorMessages.NO_CONFIGURE_FILE))

                        waiting.set(3)
                    }
                })

            }

            web.requestHandler(router::accept)
            web.listen(0) { res ->
                if (res.succeeded()) {
                    println("Awaiting configuration at http://${InetAddress.getLocalHost().hostAddress}:${res.result().actualPort()}/configure?token=$token")
                } else {
                    print("Server could not be started: ")
                    res.cause().printStackTrace()

                    waiting.set(2)
                }
            }

            while (waiting.get() == 0) {
                Thread.sleep(100)
            }

            when (waiting.get()) {
                1 -> {
                    println("Configuration successful, starting up now...")
                    //Start up
                }
                2 -> {
                    println("Web server could not be started, exiting...")
                    System.exit(ProgramReturnCodes.COULD_NOT_START_WEBSERVER)

                    return //Safety Net
                }
                3 -> {
                    println("Configuration file could not be found, exiting...")
                    System.exit(ProgramReturnCodes.NO_CONFIGURE_WEB_FILE_FOUND)

                    return //Safety Net
                }
                else -> {
                    println("Unknown web config return code of ${waiting.get()}, exiting...")
                    System.exit(ProgramReturnCodes.UNKNOWN_WEB_CONFIGURE_RESPONSE)

                    return //Safety Net
                }
            }
        }
    }
}

fun loadConfig(): Unit? {
    if (JSON_CONFIG_FILE.exists())
        return Unit
    else if (YAML_CONFIG_FILE.exists())
        return Unit

    return null
}

fun readFirstCharacter(): Char? = readLine()?.toLowerCase()?.firstOrNull()