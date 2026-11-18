// CI for bank-core: compile, run every test (Testcontainers needs Docker), publish reports, build the image.
pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    stages {
        stage('Build and test') {
            steps {
                // The executable bit can get lost on the way into git; the wrapper is a plain sh script.
                sh 'chmod +x mvnw && ./mvnw -B -ntp verify'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml, target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                    archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        stage('Docker image') {
            steps {
                sh 'docker build -t bank-core:${BUILD_NUMBER} -t bank-core:latest .'
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'target/bank-core-*.jar', fingerprint: true
        }
    }
}
