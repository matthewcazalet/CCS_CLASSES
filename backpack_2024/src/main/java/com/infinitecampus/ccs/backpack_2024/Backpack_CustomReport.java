package com.infinitecampus.ccs.backpack_2024;

import java.io.Serializable;
import java.util.ArrayList;

public class Backpack_CustomReport implements Serializable
{
	 private static final long serialVersionUID = 1L;
	 
	 public String Identifier;
	 public String Name;
	 public ArrayList<Parameter>parameters;

	 public Boolean AdhocEligible=false;
	 public Backpack_CustomReport(){parameters=new ArrayList<Parameter>();Name="";
		}
	public class Parameter{
		public String Identifier;
		public String Name;
		public String[] DisplayText;
		public String[] DisplayValue;
		public String SelectedText;
		public String SelectedValue;
		public Boolean Required=false;
		public Boolean Visible=true;
		public Boolean Repeatable=false;
		public Boolean Scheduled=false;

		public String RepeatFieldName;		
		public String ControlType;
		public int DatabaseType; //1=stored procedure;//4=calendar 
		public String DataIdentifier;	//stored procedure name
		//public String DataObjectName;//parameter name in the stored procudeure.
		public Parameter(){Name="";}
		public ArrayList<Parameter>parentparameters;//these parameter values need to be populated before generating
		public ArrayList<Parameter>childparameters; //the parameter children- the parameters that depend on this value when selected
		
	}
}
	

