def tests = [
    test_ec2_k8s_basic,
    test_noop
]

def getTextMatrix() {
    return tests
}

def test_ec2_k8s_basic = {
    node {
        git url: 'https://github.com/Juniper/container-networking-ansible.git'

        withCredentials([[$class: 'UsernamePasswordBinding', credentialsId: 'k8s-provisioner', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']]) {
            // create cluster
            sh("ansible-playbook -i localhost playbook.yml --tags=create -e job_id=$BUILD_TAG")
        }

        withCredentials([[$class: 'FileBinding', credentialsId: 'infra-ssh-key', 'SSH_PRIVATE_KEY']]) {
            sh("ansible-playbook -i localhost playbook.yml --private-key=$SSH_PRIVATE_KEY --tags=deployer-install")
            sh("ansible-playbook -i localhost playbook.yml --private-key=$SSH_PRIVATE_KEY --tags=workspace")           
        }
        {
            // provision cluster
            // validate
            // example application
            // verify
        }

        withCredentials([[$class: 'UsernamePasswordBinding', credentialsId: 'k8s-provisioner', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']]) {
            // delete cluster
        }
    }
}

def test_noop = {
    node {
        def job = hudson.model.Hudson.instance.getItem('container-networking-ansible')
        println "{$id}"
    }
}

return this
