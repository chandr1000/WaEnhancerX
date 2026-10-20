package com.waenhancer.root

/**
 * The single public door to `su`. Every call site passes a literal command id
 * present in [RootCommandRegistry]; the enforcement scanner
 * (`qa/test_root_registry_enforcement.py`) makes this structural.
 *
 * Gate sequence: registry lookup -> RootGate.isEnabled ->
 * RootParamPolicy.validate -> spec.template (defensive quoting inside) ->
 * RootEngine.exec -> outputPolicy.check -> RootAuditLog.record.
 * Blocked attempts are audited too — transparency is the point of the feature.
 */
object RegisteredRootRunner {

    @JvmStatic
    @JvmOverloads
    fun run(
        commandId: String,
        params: Map<String, String> = emptyMap(),
        callerTag: String = "unknown"
    ): RootResult {
        val spec = RootCommandRegistry.spec(commandId)
            ?: run {
                RootAuditLog.record(commandId, params, RootResult.Blocked(BlockedReason.UNREGISTERED), null, 0L, callerTag)
                return RootResult.Blocked(BlockedReason.UNREGISTERED)
            }
        if (spec.toggleable && !RootGate.isEnabled(spec.id)) {
            RootAuditLog.record(spec.id, params, RootResult.Blocked(BlockedReason.DISABLED_BY_USER), null, 0L, callerTag)
            return RootResult.Blocked(BlockedReason.DISABLED_BY_USER)
        }
        val validated = try {
            validate(spec, params)
        } catch (failure: IllegalArgumentException) {
            RootAuditLog.record(spec.id, params, RootResult.Blocked(BlockedReason.INVALID_PARAMETER), null, 0L, callerTag)
            return RootResult.Blocked(BlockedReason.INVALID_PARAMETER)
        }
        val line = try {
            spec.template(validated)
        } catch (failure: RuntimeException) {
            RootAuditLog.record(spec.id, validated, RootResult.Blocked(BlockedReason.INVALID_PARAMETER), null, 0L, callerTag)
            return RootResult.Blocked(BlockedReason.INVALID_PARAMETER)
        }
        val started = System.nanoTime()
        val execResult = try {
            RootEngine.execResult(line, spec.timeoutMs)
        } catch (failure: RuntimeException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            RootAuditLog.record(spec.id, validated, RootResult.Failed(FailureKind.SPAWN_ERROR), null, elapsed, callerTag)
            return RootResult.Failed(FailureKind.SPAWN_ERROR)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        if (!execResult.ok) {
            val kind = if (execResult.timedOut) FailureKind.TIMEOUT else FailureKind.NONZERO_EXIT
            RootAuditLog.record(spec.id, validated, RootResult.Failed(kind), null, elapsed, callerTag)
            return RootResult.Failed(kind)
        }
        val raw = execResult.output
        val ok = checkOutput(spec, raw)
        if (!ok) {
            RootAuditLog.record(spec.id, validated, RootResult.Blocked(BlockedReason.OUTPUT_POLICY_FAILED), raw, elapsed, callerTag)
            return RootResult.Blocked(BlockedReason.OUTPUT_POLICY_FAILED)
        }
        val result: RootResult = if (raw.isEmpty()) RootResult.Success else RootResult.Completed(raw)
        RootAuditLog.record(spec.id, validated, result, raw, elapsed, callerTag)
        return result
    }

    private fun validate(spec: RootCommandSpec, params: Map<String, String>): Map<String, String> {
        val validated = LinkedHashMap<String, String>(params.size)
        for (param in spec.params) {
            if (param.kind == ParamKind.CONSTANT) continue
            if (param.repeated) {
                // Repeated params arrive as a single newline-joined string; validate
                // each element against the kind-specific validator before accepting.
                val raw = params[param.name]
                if (param.required && raw.isNullOrBlank()) {
                    throw IllegalArgumentException("missing repeated param: ${param.name}")
                }
                if (raw != null) {
                    if (param.kind == ParamKind.PACKAGE_NAME) {
                        raw.split("\n").filter { it.isNotBlank() }.forEach { elem ->
                            if (!RootParamPolicy.isValidPackageName(elem)) {
                                throw IllegalArgumentException("invalid PACKAGE_NAME in repeated param: ${param.name}")
                            }
                        }
                    }
                    validated[param.name] = raw
                }
                continue
            }
            val raw = params[param.name]
            if (param.required && raw.isNullOrBlank()) {
                throw IllegalArgumentException("missing param: ${param.name}")
            }
            if (raw == null) continue
            when (param.kind) {
                ParamKind.PACKAGE_NAME -> {
                    if (!RootParamPolicy.isValidPackageName(raw)) {
                        throw IllegalArgumentException("invalid PACKAGE_NAME: ${param.name}")
                    }
                }
                ParamKind.PATH_STRICT -> {
                    if (!RootParamPolicy.isValidPathStrict(raw)) {
                        throw IllegalArgumentException("invalid PATH_STRICT: ${param.name}")
                    }
                }
                ParamKind.BASE64_TEXT -> {
                    if (!RootParamPolicy.isValidBase64Text(raw)) {
                        throw IllegalArgumentException("invalid BASE64_TEXT: ${param.name}")
                    }
                }
                ParamKind.FILE_PATH, ParamKind.DERIVED_OUTPUT, ParamKind.CONSTANT -> {
                    // FILE_PATH: app-generated cacheDir-derived path; validated at the caller.
                    // DERIVED_OUTPUT: value extracted from a previous command's policy-passing output.
                }
            }
            validated[param.name] = raw
        }
        return validated
    }

    private fun checkOutput(spec: RootCommandSpec, output: String): Boolean = when (spec.outputPolicy) {
        is OutputPolicy.Sentinel -> output.lines().lastOrNull() == spec.outputPolicy.exact
        is OutputPolicy.Regex -> spec.outputPolicy.pattern.matches(output)
        OutputPolicy.EmptyOk -> true
        OutputPolicy.RawTextCapped -> true
    }
}
