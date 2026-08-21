package com.inspiredandroid.kai.linux

import com.inspiredandroid.kai.runtime.EnvironmentHealth
import com.inspiredandroid.kai.runtime.EnvironmentRepairAction
import com.inspiredandroid.kai.runtime.EnvironmentRepairPlan
import com.inspiredandroid.kai.runtime.MoatazRuntimeContract

data class EnvironmentRepairResult(
    val health: EnvironmentHealth,
    val appliedActions: List<EnvironmentRepairAction>,
    val requiresReinstall: Boolean,
    val detail: String? = null,
)

/** Applies targeted repairs first. It never deletes the projects host directory. */
class EnvironmentRepairExecutor(private val paths: LinuxPaths) {
    fun execute(plan: EnvironmentRepairPlan): EnvironmentRepairResult {
        val applied = mutableListOf<EnvironmentRepairAction>()
        for (action in plan.actions) {
            when (action) {
                is EnvironmentRepairAction.InstallPackages -> {
                    val result = launcher().execute(
                        AptPackageManager.installCommand(action.packages),
                        timeoutSeconds = 900,
                    )
                    if (!result.success) {
                        return EnvironmentRepairResult(
                            health = EnvironmentDoctor(paths).diagnose(),
                            appliedActions = applied,
                            requiresReinstall = false,
                            detail = result.failureDetail(),
                        )
                    }
                    applied += action
                }
                EnvironmentRepairAction.RepairShellAndUsrMerge -> {
                    DebianSpec.configure(paths.rootfsDir)
                    applied += action
                }
                EnvironmentRepairAction.RestoreWorkspaceMounts -> {
                    paths.ensureLayout()
                    paths.ensureMountPoints()
                    applied += action
                }
                EnvironmentRepairAction.RestoreNativeRuntime -> {
                    paths.copyLibtalloc()
                    applied += action
                }
                EnvironmentRepairAction.ReinstallRuntimePreservingProjects -> {
                    return EnvironmentRepairResult(
                        health = EnvironmentDoctor(paths).diagnose(),
                        appliedActions = applied,
                        requiresReinstall = true,
                        detail = "Runtime identity cannot be repaired in place; projects are preserved outside the rootfs",
                    )
                }
            }
        }
        return EnvironmentRepairResult(EnvironmentDoctor(paths).diagnose(), applied, false)
    }

    private fun launcher() = ProotLauncher(
        prootPath = paths.prootPath,
        libDir = paths.libDir,
        rootfsPath = paths.rootfsDir.absolutePath,
        tmpPath = paths.tmpDir.absolutePath,
        binds = listOf(
            paths.projectsDir.absolutePath to MoatazRuntimeContract.workspaceRoot,
            paths.projectsDir.absolutePath to MoatazRuntimeContract.legacyProjectsRoot,
        ),
        extraArgs = DebianSpec.prootArgs,
        env = DebianSpec.env,
    )
}
