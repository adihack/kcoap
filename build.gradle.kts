plugins {
    id("kcoap.coverage")
    id("kcoap.docs")
}

useSettingsRepositoriesForNodeJs()

// kotlinNpmInstall (js) and kotlinWasmNpmInstall (wasmJs) each run yarn 1, which is not safe to run twice at once
// on one package cache. On a CI runner, whose cache starts cold, an overlap left build/js/node_modules without
// mocha, and every jsNodeTest failed with "Cannot find node module mocha/bin/mocha" (CI runs 42230744679 and
// 42289764293, where the wasm install overlapped the js one; runs where they ran one after the other passed).
tasks.matching { it.name == "kotlinWasmNpmInstall" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "kotlinNpmInstall" })
}
