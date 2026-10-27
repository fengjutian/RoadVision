package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.inference.ExecutionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineFactoryTest {
    @Test
    fun acceleratedProvidersFallBackToCpu() {
        assertEquals(
            listOf(ExecutionProvider.XNNPACK, ExecutionProvider.CPU),
            providerCandidates(ExecutionProvider.XNNPACK),
        )
        assertEquals(
            listOf(ExecutionProvider.NNAPI, ExecutionProvider.CPU),
            providerCandidates(ExecutionProvider.NNAPI),
        )
        assertEquals(listOf(ExecutionProvider.CPU), providerCandidates(ExecutionProvider.CPU))
    }
}
