package com.lmqr.ha9_comp_service.command_runners

interface CommandRunner {
    fun runCommands(cmds: Array<String>)
    fun setDisplayAwake(awake: Boolean) {}
    fun onDestroy()
}
