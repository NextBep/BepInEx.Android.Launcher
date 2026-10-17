package com.bepinex.android

/**
 * Secondary stub activity used when the game starts its own activities at
 * runtime: [com.bepinex.android.hooks.GameRuntime]'s instrumentation hook wraps
 * them into this component (declared in our manifest) and then substitutes the
 * real activity instance during creation.
 */
class SecondaryStubActivity : StubActivity()
