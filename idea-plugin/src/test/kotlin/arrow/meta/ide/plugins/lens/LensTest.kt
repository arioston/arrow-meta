package arrow.meta.ide.plugins.lens

import arrow.meta.ide.IdeMetaPlugin
import arrow.meta.ide.resources.ArrowIcons
import arrow.meta.ide.testing.IdeTest
import arrow.meta.ide.testing.dsl.lineMarker.LineMarkerDescription
import arrow.meta.ide.testing.env.IdeTestSetUp
import arrow.meta.ide.testing.env.ideTest
import org.junit.Test

class LensTest : IdeTestSetUp() {
  @Test
  fun `Optics Test for LineMarkers`() =
    ideTest(
      myFixture = myFixture,
      ctx = IdeMetaPlugin()
    ) {
      listOf<IdeTest<IdeMetaPlugin, LineMarkerDescription>>(
        IdeTest(
          code = LensTestCode.code1,
          test = { code, myFixture, _ ->
            collectLM(code, myFixture, ArrowIcons.OPTICS)
          },
          result = resolvesWhen("LineMarkerTest for 3 LM ") {
            it.lineMarker.size == 3 && it.slowLM.isEmpty()
          }
        )
      )
    }
}