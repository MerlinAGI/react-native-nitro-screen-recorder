package com.margelo.nitro.nitroscreenrecorder

import android.view.Surface

interface RecordingSurfaceRouter {
  fun attach(surface: Surface, width: Int, height: Int): Surface
  fun detach()
  fun close()
}
