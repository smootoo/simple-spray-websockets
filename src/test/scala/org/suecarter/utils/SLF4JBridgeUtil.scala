package org.suecarter.utils

import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * Wrapper around initialising the java.util.logging to SLF4J bridge to
 * ensure that it is only initialise once.
 */
object SLFJ4BridgeUtil {
  def initialiseBridge(): Unit = synchronized {

    if (!SLF4JBridgeHandler.isInstalled) {
      // remove existing handlers attached to j.u.l root logger
      SLF4JBridgeHandler.removeHandlersForRootLogger()
      // add SLF4JBridgeHandler to j.u.l's root logger, should be done once during
      // the initialization phase of your application
      SLF4JBridgeHandler.install()
    }
  }
}
