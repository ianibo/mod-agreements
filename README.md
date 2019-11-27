# mod-agreements

Copyright (C) 2018-2019 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

# Introduction - developers looking to enhance the resources that mod-agreements provides

Mod-Agreements is a FOLIO module to manage agreements which control an institutions access to resources. Normally, this will be 
subscribed access to packages of electronic resources, although the aim is to make the affordances offered by mod-agreements as general as possible.

Mod-Agreements can create agreements that control access to content bundled into packages and defined in knowledgebase systems, it can 
identify specific electronic or print resources, and act as a bridge between those resources and associated licenses and purchase documents.

Developers looking to access the services exposed by mod-agreements can find more information in the following sections

## Resources exposed by this module

### /erm/sas resource - SubscriptionAgreements

The /erm/sas resource allows module clients to Create, Retrieve, Update and Delete SubscriptionAgreement entities, and to search for SAs. [See the documentation](./doc/subscription_agreement_resource.md)

### /erm/refdataValues

This resource allows module owners to obtain tenant extensible lists of controlled values. The URL pattern is "/erm/refdataValues/$domain/$property" 

As of 2019-02-20 the following are defined:

| Refdata Category | URL for values |
| --- | --- |
|TitleInstance.SubType|/erm/refdataValues/TitleInstance/SubType|
|TitleInstance.Type|/erm/refdataValues/TitleInstance/Type|
|InternalContact.Role|/erm/refdataValues/InternalContact/Role|
|SubscriptionAgreementOrg.Role|/erm/refdataValues/SubscriptionAgreementOrg/Role|
|SubscriptionAgreement.AgreementType|/erm/refdataValues/SubscriptionAgreement/AgreementType|
|SubscriptionAgreement.RenewalPriority|/erm/refdataValues/SubscriptionAgreement/RenewalPriority|
|Global.Yes_No|/erm/refdataValues/Global/Yes_No|
|SubscriptionAgreement.AgreementStatus|/erm/refdataValues/SubscriptionAgreement/AgreementStatus|
|Pkg.Type|/erm/refdataValues/Pkg/Type|
|IdentifierOccurrence.Status|/erm/refdataValues/IdentifierOccurrence/Status|

## ModuleDescriptor

https://github.com/folio-org/mod-agreements/blob/master/service/src/main/okapi/ModuleDescriptor-template.json

# For module developers looking to extend or modify the resources presented by this module

This is the main starter repository for the Grails-based OLF - ERM backend modules.

- [Getting started](service/docs/getting-started.md "Getting started")

## Additional information

### Issue tracker

See project [ERM](https://issues.folio.org/projects/ERM)
at the [FOLIO issue tracker](https://dev.folio.org/guidelines/issue-tracker/).

### Other documentation

Other [modules](https://dev.folio.org/source-code/#server-side) are described,
with further FOLIO Developer documentation at [dev.folio.org](https://dev.folio.org/)


## Running using grails run-app with the vagrant-db profile

    grails -Dgrails.env=vagrant-db run-app


## Initial Setup

Most developers will run some variant of the following commands the first time through

### In window #1

Start the vagrant image up from the project root

    vagrant destroy
    vagrant up

Sometimes okapi does not start cleanly in the vagrant image - you can check this with

    vagrant ssh

then once logged in

    docker ps

should list running images - if no processes are listed, you will need to restart okapi (In the vagrant image) with

    sudo su - root
    service okapi stop
    service okapi start

Finish the part off with

    tail -f /var/log/folio/okapi/okapi.log

### In window #2

Build and run mod-agreements stand alone

    cd service
    grails war
    ../scripts/run_external_reg.sh

### In window #3

Register the module and load some test data

  cd scripts
  ./register_and_enable.sh
  ./dev_submit_pkg.sh
  ./dev_trigger_kb_sync.sh 

### In window #4

Run up a stripes platform containing erm

See [UI Agreements quick start](https://github.com/folio-org/ui-agreements/blob/master/README.md)

This section is run in a local setup, not from any particular checked out project, YMMV

    cd ../platform/stripes/platform-erm
    stripes serve ./stripes.config.js --has-all-perms



You should get back

Waiting for webpack to build...
Listening at http://localhost:3000

and then be able to access the app

  

