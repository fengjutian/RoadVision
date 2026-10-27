package com.roadvision.roadsense.inference

import android.content.Context
import java.security.MessageDigest

object ModelAssetVerifier {
    fun verify(context: Context, assetPath: String, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(expectedSha256, ignoreCase = true)) {
            "Model hash mismatch for $assetPath: expected $expectedSha256, got $actual"
        }
    }
}
