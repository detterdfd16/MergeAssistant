package org.example

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import java.util.*
import kotlin.system.exitProcess

data class ParsedBody(
    val message: String,
    val documentation_url: String,
    val status: Int,
)

data class Repository(
    val name: String,
    val full_name: String,
)

data class Branch(
    val name: String,
    val commit: Commit,
)

data class Commit(
    val sha: String,
)

class MergeAssistant(private val token: String, private val owner: String) {
    private val apiUrl = "https://api.github.com"
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    // Error handler wrapper in case of failed responses
    private suspend fun isValidResponseStatus(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            val parsedBody: ParsedBody = Gson().fromJson(body, ParsedBody::class.java)
            println("Merge Assistant Error ${parsedBody.status}: ${parsedBody.message}")
            exitProcess(1)
        }
    }

    // Helper function to validate provided auth token, checks only token validity without checking required accesses
    suspend fun checkLoginDetails() {
        val response = client.get("$apiUrl/user") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Test")
            }
        }
        isValidResponseStatus(response)
    }

    // Helper function to get List of all Repositories of authorized user
    suspend fun getReposList(): List<Repository> {
        val response = client.get("$apiUrl/user/repos") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Sample-App")
            }
        }
        isValidResponseStatus(response)

        val responseBody: String = response.bodyAsText()

        // Deserialize the JSON string into List<Repository>
        val listType = object : TypeToken<List<Repository>>() {}.type
        val repositories: List<Repository> = Gson().fromJson(responseBody, listType)

        return repositories
    }

    // Helper function for getting branch SHA by repository and branch name
    suspend fun getBranchSHA(repo: Repository, branchName: String): String {
        val response = client.get("$apiUrl/repos/$owner/${repo.name}/branches/$branchName") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Sample-App")
            }
        }
        isValidResponseStatus(response)
        val branchData = Gson().fromJson(response.bodyAsText(), Branch::class.java)
        return branchData.commit.sha
    }

    // Helper function to create new branch from SHA of already existing branch
    suspend fun createBranch(repo: Repository, sha: String, newBranchName: String) {
        val response = client.post("$apiUrl/repos/$owner/${repo.name}/git/refs") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Sample-App")
            }
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "ref" to "refs/heads/$newBranchName", "sha" to sha
                )
            )
        }
        isValidResponseStatus(response)
        println("New branch '$newBranchName' created.")
    }

    // Helper function to create text file
    suspend fun createTextFile(repo: Repository, text: String, filename: String, newBranchName: String) {
        val createFileResponse = client.put("$apiUrl/repos/$owner/${repo.name}/contents/$filename") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Sample-App")
            }
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "message" to "Add $filename",
                    "content" to Base64.getEncoder().encodeToString(text.toByteArray()),
                    "branch" to newBranchName
                )
            )
        }
        isValidResponseStatus(createFileResponse)
        println("File '$filename' created in branch '$newBranchName'.")
    }

    suspend fun createPullRequest(
        repo: Repository, branchName: String, newBranchName: String, title: String, body: String
    ) {
        val pullRequestResponse = client.post("https://api.github.com/repos/$owner/${repo.name}/pulls") {
            headers {
                append("Accept", "application/vnd.github+json")
                append("Authorization", "token $token")
                append("User-Agent", "Ktor-Sample-App")
            }
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "title" to title, "head" to newBranchName, "base" to branchName, "body" to body
                )
            )
        }
        isValidResponseStatus(pullRequestResponse)
        println("Pull request from '$newBranchName' to '$branchName' with title '$title' created")
    }

    suspend fun addTextFile(repo: Repository, filename: String, text: String, branchName: String) {
        val newBranchName = "feat/$filename"

        val sha = getBranchSHA(repo, branchName)

        createBranch(repo, sha, newBranchName)

        createTextFile(repo, text, filename, newBranchName)

        createPullRequest(
            repo, branchName, newBranchName, "Added $filename", "This pull requests adds $filename to root folder"
        )

    }

    fun close() {
        client.close()
    }
}

suspend fun main() {
    println("Loading credentials from file")
    val properties = Properties().apply {
        val resourceStream = ClassLoader.getSystemResourceAsStream("config.properties")
        if (resourceStream != null) {
            load(resourceStream)
        } else {
            println("config.properties not found in resources folder")
            return
        }
    }

    val token = properties.getProperty("github.token")
    val username = properties.getProperty("github.username")

    if (token.isNullOrEmpty() || username.isNullOrEmpty()) {
        println("Token or username not found in config.properties")
        return
    }

    println("Loaded successfully: $token, $username")

    val mergeAssistant = MergeAssistant(token, username)

    mergeAssistant.checkLoginDetails()

    val repositories = mergeAssistant.getReposList()

    repositories.forEachIndexed { i, repo ->
        println("${i + 1}) Repo Name: ${repo.name}, Full Name: ${repo.full_name}")
    }

    print("Select repository to make change(input a number): ")
    val choice = readLine()?.toIntOrNull()
    if (choice == null || choice !in 1..repositories.size) {
        println("Invalid repository number provided")
        return
    }
    val selectedRepository = repositories[choice - 1]

    mergeAssistant.addTextFile(selectedRepository, "hello.txt", "Hello, World!", "master")
    mergeAssistant.close()
    println("Merge Assistant finished execution")
}
