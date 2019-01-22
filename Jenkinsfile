def jenkinsfile


def overrides = [
    scriptVersion  : 'v6',
    pipelineScript: 'https://git.aurora.skead.no/scm/ao/aurora-pipeline-scripts.git',
    checkstyle: false,
    docs: false,
    sonarQube: false,
    credentialsId: "github",
    versionStrategy: [
      [branch: 'master', versionHint: '1.0']
    ]
]

fileLoader.withGit(overrides.pipelineScript,, overrides.scriptVersion) {
   jenkinsfile = fileLoader.load('templates/leveransepakke')
}
jenkinsfile.gradle(overrides.scriptVersion, overrides)
