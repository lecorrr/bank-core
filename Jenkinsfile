// CI for bank-core: compile, run every test (Testcontainers needs Docker), publish reports, build the image.
// With the k3d cluster of bank-open-api (DEPLOY_TO_K3D=true, see docs/jenkins.md) it also deploys the image there.
pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    environment {
        // The k3d registry, as the host's Docker sees it; the cluster pulls the same names from it.
        REGISTRY = 'localhost:5050'
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
                script {
                    // The commit names the image, so the cluster always shows which code runs.
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                }
                sh 'docker build -t bank-core:${IMAGE_TAG} -t bank-core:latest .'
            }
        }

        stage('Deploy to k3d') {
            when {
                environment name: 'DEPLOY_TO_K3D', value: 'true'
            }
            steps {
                sh 'helm lint charts/bank-core'
                sh 'docker tag bank-core:${IMAGE_TAG} ${REGISTRY}/bank-core:${IMAGE_TAG}'
                sh 'docker push ${REGISTRY}/bank-core:${IMAGE_TAG}'
                // Waits until the new pod is ready; a release that never gets there is rolled back.
                // --set-string, because --set would turn a commit id made only of digits into a number.
                sh '''
                    helm upgrade --install bank-core charts/bank-core --namespace bank \
                        --set image.repository=${REGISTRY}/bank-core --set-string image.tag=${IMAGE_TAG} \
                        --rollback-on-failure --timeout 5m
                '''
                // Helm is done once the new pod is ready, but the old one answers on during its preStop pause,
                // until the Service leaves it out. The job ends when only pods of this image are left, so
                // bank-open-api's smoke test, run next, meets this version only.
                sh '''
                    deadline=$(( $(date +%s) + 120 ))
                    while kubectl --namespace bank get pods -l app.kubernetes.io/instance=bank-core \
                        -o jsonpath='{range .items[*]}{.metadata.labels.app\\.kubernetes\\.io/version}{"\\n"}{end}' \
                        | grep -qvxF "${IMAGE_TAG}"; do
                        if [ "$(date +%s)" -ge "$deadline" ]; then
                            echo "Pods of another version are still there after two minutes:"
                            kubectl --namespace bank get pods -l app.kubernetes.io/instance=bank-core
                            exit 1
                        fi
                        sleep 2
                    done
                '''
            }
            post {
                failure {
                    sh 'kubectl --namespace bank get pods || true'
                    sh 'kubectl --namespace bank get events --sort-by=.lastTimestamp | tail -n 20 || true'
                }
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'target/bank-core-*.jar', fingerprint: true
        }
    }
}
