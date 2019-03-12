package tagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/tags\?repoUrl=.*/),
        test('/tags?repoUrl=docker1.no/no_skatteetaten_aurora_demo/whoami')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/TagResource.json'))
  }
}