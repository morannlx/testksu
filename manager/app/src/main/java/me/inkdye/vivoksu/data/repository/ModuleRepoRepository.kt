package me.inkdye.vivoksu.data.repository

import me.inkdye.vivoksu.data.model.RepoModule

interface ModuleRepoRepository {
    suspend fun fetchModules(): Result<List<RepoModule>>
}
