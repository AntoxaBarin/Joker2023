package me.markoutte.joker.HTMLparse.step2

import me.markoutte.joker.helpers.ComputeClassWriter
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.objectweb.asm.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.math.min
import kotlin.random.Random

@ExperimentalStdlibApi
fun main(args: Array<String>) {
    val options = Options().apply {
        addOption("c", "class", true, "Java class fully qualified name")
        addOption("m", "method", true, "Method to be tested")
        addOption("cp", "classpath", true, "Classpath with libraries")
        addOption("t", "timeout", true, "Maximum time for fuzzing in seconds")
        addOption("s", "seed", true, "The source of randomness")
    }
    val parser = DefaultParser().parse(options, args)
    val className = parser.getOptionValue("class")
    val methodName = parser.getOptionValue("method")
    val classPath = parser.getOptionValue("classpath")
    val timeout = parser.getOptionValue("timeout")?.toLong() ?: 10L
    val seed = parser.getOptionValue("seed")?.toInt() ?: Random.nextInt()
    val random = Random(seed)

    println("Running: $className.$methodName) with seed = $seed")
    val errors = mutableSetOf<String>()
    val b = ByteArray(300)
    val start = System.nanoTime()

    val javaMethod = try {
        loadJavaMethod(className, methodName, classPath)
    } catch (t: Throwable) {
        println("Method $className#$methodName is not found")
        return
    }

    val seeds = mutableMapOf<Int, ByteArray>(
        -1 to generateRandomHTML(depth=30).asByteArray(b.size)!!
    )

    while(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(timeout)) {
        val buffer = seeds.values.randomOrNull(random)?.let(Random::mutate)
            ?: b.apply(random::nextBytes)
        val inputValues = generateInputValues(javaMethod, buffer)
        val inputValuesString = "${javaMethod.name}: ${inputValues.contentDeepToString()}"
        try {
            ExecutionPath.id = 0
            javaMethod.invoke(null, *inputValues).apply {
                val seedId = ExecutionPath.id
                if (seeds.putIfAbsent(seedId, buffer) == null) {
                    println("New seed added: ${seedId.toHexString()}")
                }
            }
        } catch (e: InvocationTargetException) {
            if (errors.add(e.targetException::class.qualifiedName!!)) {
                val errorName = e.targetException::class.simpleName
                println("New error found: $errorName")
                val path = Paths.get("report$errorName.txt")
                Files.write(path, listOf(
                    "${e.targetException.stackTraceToString()}\n",
                    "$inputValuesString\n",
                    "${buffer.contentToString()}\n",
                ))
                Files.write(path, buffer, StandardOpenOption.APPEND)
                println("Saved to: ${path.fileName}")
            }
        }
    }

    println("Seeds found: ${seeds.size}")
    println("Errors found: ${errors.size}")
    println("Time elapsed: ${TimeUnit.NANOSECONDS.toMillis(
        System.nanoTime() - start
    )} ms")
}

fun loadJavaMethod(className: String, methodName: String, classPath: String): Method {
    val libraries = classPath
        .split(File.pathSeparatorChar)
        .map { File(it).toURI().toURL() }
        .toTypedArray()
    val classLoader = object : URLClassLoader(libraries) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            return if (name.startsWith(className.substringBeforeLast('.'))) {
                transformAndGetClass(name).apply {
                    if (resolve) resolveClass(this)
                }
            } else {
                super.loadClass(name, resolve)
            }
        }

        fun transformAndGetClass(name: String): Class<*> {
            val owner = name.replace('.', '/')
            var bytes =
                getResourceAsStream("$owner.class")!!.use { it.readBytes() }
            val reader = ClassReader(bytes)
            val cl = this
            val writer = ComputeClassWriter(
                reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES, cl
            )
            val transformer = object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    return object : MethodVisitor(
                        Opcodes.ASM9,
                        super.visitMethod(
                            access, name, descriptor, signature, exceptions
                        )
                    ) {
                        val ownerName =
                            ExecutionPath.javaClass.canonicalName.replace('.', '/')
                        val fieldName = "id"

                        override fun visitLineNumber(line: Int, start: Label?) {
                            visitFieldInsn(
                                Opcodes.GETSTATIC, ownerName, fieldName, "I"
                            )
                            visitLdcInsn(line)
                            visitInsn(Opcodes.IADD)
                            visitFieldInsn(
                                Opcodes.PUTSTATIC, ownerName, fieldName, "I"
                            )
                            super.visitLineNumber(line, start)
                        }
                    }
                }
            }
            reader.accept(transformer, ClassReader.SKIP_FRAMES)
            bytes = writer.toByteArray().also {
                if (name == className) {
                    Paths.get("Instrumented.class").writeBytes(it)
                }
            }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
    val javaClass = classLoader.loadClass(className)
    val javaMethod = javaClass.declaredMethods.first {
        "${it.name}(${it.parameterTypes.joinToString(",") {
                c -> c.typeName
        }})" == methodName
    }
    return javaMethod
}

fun generateInputValues(method: Method, data: ByteArray): Array<Any> {
    val buffer = ByteBuffer.wrap(data)
    val parameterTypes = method.parameterTypes
    return Array(parameterTypes.size) {
        when (parameterTypes[it]) {
            Int::class.java -> buffer.get().toInt()
            IntArray::class.java -> IntArray(buffer.get().toUByte().toInt()) {
                buffer.get().toInt()
            }
            String::class.java -> String(ByteArray(
                buffer.get().toUByte().toInt() + 1
            ) {
                buffer.get()
            }, Charset.forName("koi8"))
            else -> error("Cannot create value of type ${parameterTypes[it]}")
        }
    }
}

object ExecutionPath {
    @JvmField
    var id: Int = 0
}


fun Random.mutate(buffer: ByteArray): ByteArray {
    val htmlString = buffer.toString(Charsets.UTF_8)
    val mutatedHtml = StringBuilder(htmlString)

    val addTagProbability = 0.25
    val swapTagProbability = 0.05
    val tagRegex = "<[^>]+>".toRegex()
    var tags = tagRegex.findAll(htmlString).map { it.value }.toList()

    if (tags.isNotEmpty() && Random.nextDouble() < addTagProbability) {
        val randomTag = tags.random()
        val insertionPoint = Random.nextInt(mutatedHtml.length)
        mutatedHtml.insert(insertionPoint, randomTag)
    }

    tags = tagRegex.findAll(htmlString).map { it.value }.toList()
    if (tags.isNotEmpty() && Random.nextDouble() < swapTagProbability) {
        val fstRandomTag = tags.random()
        val sndRandomTag = tags.random()
        val fstTagIndex = mutatedHtml.indexOf(fstRandomTag)
        val sndTagIndex = mutatedHtml.indexOf(sndRandomTag)
        val startIndex = min(fstTagIndex, sndTagIndex)
        val endIndex = fstTagIndex + sndTagIndex - startIndex - 1

        if (fstTagIndex != sndTagIndex && fstTagIndex != -1 && sndTagIndex != -1) {
            val subArray = buffer.sliceArray(startIndex until min(endIndex, buffer.size - 1))
            subArray.shuffle(Random)
            for (i in 0 until endIndex - startIndex) {
                if (i + startIndex >= buffer.size || i >= subArray.size) {
                    break
                }
                buffer[i + startIndex] = subArray[i]
            }
        }
    }

    return mutatedHtml.toString().toByteArray(Charsets.UTF_8)
}

fun Any.asByteArray(length: Int): ByteArray? = when (this) {
    is String -> {
        val bytes = toByteArray(Charset.forName("koi8"))
        ByteArray(length) {
            if (it == 0) {
                (bytes.size - 1).toUByte().toByte()
            } else if (it - 1 < bytes.size) {
                bytes[it - 1]
            } else {
                0
            }
        }
    }
    else -> null
}

val tags = arrayOf("div", "span", "p", "a", "h1", "h2", "ul", "li", "ol", "abc", ".,.,.,", ".")
val attributes = arrayOf("class", "id", "style", "color", "bool", "content")
val texts = arrayOf("Fuzz", "Lorem ipsum dolor sit amet", "AAAAAAAAAA////////.........///////.../&&")

fun generateRandomHTML(depth: Int): String {
    if (depth == 0) {
        return texts.random()
    }

    val html = StringBuilder("<${tags.random()}")
    if (Random.nextBoolean()) {
        html.append(" ${attributes.random()}=\"${texts.random()}\"")
    }
    html.append(">")
    if (Random.nextBoolean()) {
        html.append(generateRandomHTML(depth - 1))
    } else {
        html.append(texts.random())
    }
    html.append("</${tags.random()}>")
    return html.toString()
}