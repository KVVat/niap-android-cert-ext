package com.android.niap.cert.service.engine

import com.android.niap.cert.manager.CsrSpec

interface INiapCsrEngine {
    fun generateCsr(alias: String, subjectDn: String, sans: List<String>, spec: CsrSpec = CsrSpec()): ByteArray
}
