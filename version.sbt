val gitHeadCommitSha = settingKey[String]("current git commit SHA")

gitHeadCommitSha in ThisBuild := Process("git rev-parse --short HEAD").lines.head

version in ThisBuild := "0.6.0-" + gitHeadCommitSha.value
