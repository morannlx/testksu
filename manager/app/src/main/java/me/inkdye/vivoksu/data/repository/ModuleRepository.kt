package me.inkdye.vivoksu.data.repository

import me.inkdye.vivoksu.data.model.Module
import me.inkdye.vivoksu.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
