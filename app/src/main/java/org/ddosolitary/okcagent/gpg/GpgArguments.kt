package org.ddosolitary.okcagent.gpg

import android.content.Context

class GpgArguments(
	val options: Map<String, String?>,
	val arguments: List<String>,
	val warnings: List<String> = emptyList()
) {
	fun validate() {
		val groups = listOf(
			options.containsKey("encrypt") || options.containsKey("sign") && !(options.containsKey("clear-sign") || options.containsKey("detach-sign")),
			options.containsKey("clear-sign"), options.containsKey("detach-sign"),
			options.containsKey("decrypt") || options.containsKey("verify"),
			options.containsKey("export"), options.containsKey("backup"),
			options.containsKey("export-ssh-key"), options.containsKey("check-permission"),
			options.containsKey("list-config")
		)
		require(groups.count { it } == 1) { "Select one action" }
		require(!options.containsKey("sign") || options.containsKey("encrypt") || options.containsKey("clear-sign") || options.containsKey("detach-sign")) {
			"Signed-packet --sign is not supported. Use --clear-sign or --detach-sign."
		}
		require(!options.containsKey("metadata") || options.containsKey("decrypt")) { "--metadata requires --decrypt" }
		require(!options.containsKey("backup-secret") || options.containsKey("backup")) { "--backup-secret requires --backup" }
		require(options["status-fd"] == null || options["status-fd"] == "2") { "Only --status-fd 2 is supported" }
		options["compress-level"]?.let {
			require(it.toIntOrNull() in 0..9) { "Compression level must be between 0 and 9" }
		}
		val maximum = if (options.containsKey("verify")) 2 else 1
		if (!options.containsKey("export") && !options.containsKey("backup")) {
			require(arguments.size <= maximum) { "Too many input files or key selectors" }
		}
		if (options.containsKey("check-permission") || options.containsKey("list-config")) {
			require(arguments.isEmpty()) { "This action does not accept input files" }
		}
	}

	companion object {
		private data class OptionInfo(val name: String, val hasValue: Boolean = false, val multiple: Boolean = false)
		private val flags = listOf(
			"sign", "clear-sign", "detach-sign", "encrypt", "decrypt", "verify",
			"armor", "quiet", "yes", "no", "verbose", "no-verbose", "list-config",
			"export", "export-ssh-key", "backup", "backup-secret", "metadata", "check-permission",
			"no-batch", "use-agent", "no-secmem-warning", "no-permission-warning"
		)
		private val values = listOf("output", "local-user", "default-user", "default-key", "status-fd", "compress-level", "set-filename")
		private val optionList = flags.map { OptionInfo(it) } + values.map { OptionInfo(it, true) } +
			listOf(OptionInfo("recipient", true, true), OptionInfo("encrypt-to", true, true))
		private val aliases = mapOf("clearsign" to "clear-sign", "bzip2-compress-level" to "compress-level")
		private val shortNames = mapOf('s' to "sign", 'b' to "detach-sign", 'e' to "encrypt", 'd' to "decrypt",
			'a' to "armor", 'r' to "recipient", 'o' to "output", 'u' to "local-user", 'q' to "quiet", 'v' to "verbose")

		@Suppress("UNUSED_PARAMETER")
		fun parse(context: Context, args: Collection<String>?): GpgArguments {
			val options = mutableMapOf<String, String?>()
			val arguments = mutableListOf<String>()
			var pending: OptionInfo? = null
			var positional = false
			fun find(name: String): OptionInfo = optionList.find { it.name == (aliases[name] ?: name) }
				?: throw IllegalArgumentException("--$name is not supported. Use OpenKeychain for key management and authorization.")
			fun set(info: OptionInfo, value: String?) {
				options[info.name] = if (info.multiple && options.containsKey(info.name)) "${options[info.name]}\u0000$value" else value
			}
			for (argument in args ?: emptyList()) {
				require(!argument.contains('\u0000')) { "Arguments must not contain NUL" }
				val expected = pending
				when {
					expected != null -> { set(expected, argument); pending = null }
					positional -> arguments.add(argument)
					argument == "--" -> positional = true
					argument.startsWith("--") -> {
						val name = argument.substring(2).substringBefore('=')
						val info = find(name)
						if (argument.contains('=')) {
							require(info.hasValue) { "--$name does not take a value" }
							set(info, argument.substringAfter('='))
						} else if (info.hasValue) pending = info else set(info, null)
					}
					argument.startsWith('-') && argument != "-" -> {
						for (index in 1 until argument.length) {
							val name = shortNames[argument[index]] ?: throw IllegalArgumentException("Unknown option -${argument[index]}")
							val info = find(name)
							if (info.hasValue) {
								if (index + 1 < argument.length) set(info, argument.substring(index + 1)) else pending = info
								break
							} else set(info, null)
						}
					}
					else -> arguments.add(argument)
				}
			}
			require(pending == null) { "--${pending?.name} requires a value" }
			return GpgArguments(options, arguments)
		}
	}
}
