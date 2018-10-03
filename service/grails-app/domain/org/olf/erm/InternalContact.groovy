package org.olf.erm
import org.olf.general.Org
import org.olf.general.RefdataValue
import org.olf.general.refdata.Defaults
import grails.gorm.MultiTenant

public class InternalContact implements MultiTenant<InternalContact>{
	
	String id
	String user
	String lastName
	String firstName
//	String role
	@Defaults(['Agreement owner', 'Subject specialist']) // Defaults to create for this property.
	RefdataValue role
	
	static belongsTo = [
		owner: SubscriptionAgreement
	]

    static mapping = {
//		table 'internal_contact'
                   id column: 'ic_id', generator: 'uuid', length:36
              version column: 'ic_version'
                owner column: 'ic_owner_fk'
				 user column: 'ic_user_fk'
			 lastName column: 'ic_last_name'
			firstName column: 'ic_first_name'
			     role column: 'ic_role'
  }

  static constraints = {
	  owner(nullable:false, blank:false);
	  user(nullable:true, blank:false);
	  lastName(nullable:true, blank:false);
	  firstName(nullable:true, blank:false);
	  role(nullable:true, blank:false);
  }
}
