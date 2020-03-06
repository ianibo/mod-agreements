
psql -U folio_admin -h localhost okapi_modules

set search_path to diku_mod_agreements, public;

# Dump schema only
pg_dump --schema-only -U folio_admin -h localhost -n diku_mod_agreements okapi_modules

select * from diku_olf_erm.title_instance where ti_title like 'cancer%';


select ti_title from diku_olf_erm.title_instance where ti_title like 'Clini%';


select 'Dump all package, title....'

select pkg_name, ti_title
from diku_olf_erm.package_content_item,
     diku_olf_erm.package,
     diku_olf_erm.platform_title_instance,
     diku_olf_erm.title_instance
where pci_pkg_fk = pkg_id
  and pci_pti_fk = pti_id
  and pti_ti_fk = ti_id;


select 'Dump all package, title, platform....'

select pkg_name, ti_title, pt_name
from diku_olf_erm.package_content_item,
     diku_olf_erm.package,
     diku_olf_erm.platform_title_instance,
     diku_olf_erm.title_instance,
     diku_olf_erm.platform
where pci_pkg_fk = pkg_id
  and pci_pti_fk = pti_id
  and pti_ti_fk = ti_id
  and pti_pt_fk = pt_id;


select 'List all remote KB sources'

select * from diku_olf_erm.remotekb;

select * from diku_olf_erm.package;


select 'List schemas'
\dn

select 'describe title_instance'
\dt diku_olf_erm.title_instance


select 'Full text goodness Score (1.0)'
SELECT ti_title, similarity(ti_title, 'Clinical Cancer Drugs') As sim_score
FROM diku_olf_erm.title_instance
WHERE ti_title % 'Clinical Cancer Drugs' AND similarity(ti_title, 'Clinical Cancer Drugs') > 0.35
ORDER BY ti_title;

select 'Full text goodness (Score 0.6)'
SELECT ti_title, similarity(ti_title, 'The Journal of Clinical Cancer Drugs') As sim_score
FROM diku_olf_erm.title_instance
WHERE ti_title % 'The Journal of Clinical Cancer Drugs' AND similarity(ti_title, 'The Journal of Clinical Cancer Drugs') > 0.35
ORDER BY ti_title;


select 'Full text goodness (Score 0.45)'
SELECT ti_title, similarity(ti_title, 'The Journal of Clonical Cancer Drugs') As sim_score
FROM diku_olf_erm.title_instance
WHERE ti_title % 'The Journal of Clonical Cancer Drugs' AND similarity(ti_title, 'The Journal of Clonical Cancer Drugs') > 0.35
ORDER BY ti_title;

SELECT ti_title, similarity(ti_title, 'Current Medicinal Chemistry -Anti-Infective Agents') As sim_score
FROM diku_olf_erm.title_instance
WHERE ti_title % 'Current Medicinal Chemistry -Anti-Infective Agents' AND similarity(ti_title, 'Current Medicinal Chemistry -Anti-Infective Agents') > 0.35
ORDER BY ti_title;


select 'List all packages where title contining the title term aquatic appear';

select pkg_name, ti_title, similarity(ti_title, 'aquatic')
from diku_olf_erm.package_content_item,
     diku_olf_erm.package,
     diku_olf_erm.platform_title_instance,
     diku_olf_erm.title_instance
where pci_pkg_fk = pkg_id
  and pci_pti_fk = pti_id
  and pti_ti_fk = ti_id
  and ti_title % 'aquatic'


select pkg_name, ti_title, similarity(ti_title, 'aquatic insects')
from diku_olf_erm.package_content_item,
     diku_olf_erm.package,
     diku_olf_erm.platform_title_instance,
     diku_olf_erm.title_instance
where pci_pkg_fk = pkg_id
  and pci_pti_fk = pti_id
  and pti_ti_fk = ti_id
  and ti_title % 'aquatic insects'
  and similarity(ti_title, 'aquatic insects') > 0.6;


select pkg_name, pkg_source, ti_title, similarity(ti_title, 'Yearbook for traditional music')
from diku_olf_erm.package_content_item,
     diku_olf_erm.package,
     diku_olf_erm.platform_title_instance,
     diku_olf_erm.title_instance
where pci_pkg_fk = pkg_id
  and pci_pti_fk = pti_id
  and pti_ti_fk = ti_id
  and ti_title % 'Yearbook for traditional music'
  and similarity(ti_title, 'Yearbook for traditional music') > 0.6;


CREATE VIEW diku_olf_erm.all_electronic_resources (type, pkg_id, pti_id, name, plat_fk) as ( select 'pkg', pkg_id, null, pkg_name from diku_olf_erm.package, null ) UNION ( select 'title', null, pti_id, ti_title, pti_pt_fk from diku_olf_erm.platform_title_instance, diku_olf_erm.title_instance where pti_ti_fk = ti_id )




select count(*) as y0_ from title_instance this_ 
                          inner join erm_resource this_1_ on this_.id=this_1_.id 
			  inner join platform_title_instance pi1_ on this_.id=pi1_.pti_ti_fk 
			  left outer join erm_resource pi1_1_ on pi1_.id=pi1_1_.id 
			  left outer join package_content_item pi_po2_ on pi1_.id=pi_po2_.pci_pti_fk 
			  left outer join erm_resource pi_po2_1_ on pi_po2_.id=pi_po2_1_.id 
			  left outer join package pi_po_pkg3_ on pi_po2_.pci_pkg_fk=pi_po_pkg3_.id 
			  left outer join erm_resource pi_po_pkg3_1_ on pi_po_pkg3_.id=pi_po_pkg3_1_.id
