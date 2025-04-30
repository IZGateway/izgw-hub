# IZ Gateway Release 2.2.2
The IZ Gateway Hub 2.2.2 release
* IGDD-1962 Fix for CVE-2025-22228. Description of the CVE that is being fixed: BCryptPasswordEncoder.matches(CharSequence,String) will incorrectly return true for passwords larger than 72 characters as long as the first 72 characters are the same.

