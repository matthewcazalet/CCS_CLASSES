package com.infinitecampus.ccs.backpack_2024;

import java.io.Serializable;
import java.time.LocalDateTime;

public class Backpack implements Serializable {
	private static final long serialVersionUID = 1L;
	public String Identifier;
	public String DocumentName;
	public int personID;
	public java.time.LocalDateTime PublishDate;
	public Boolean NewDocument = Boolean.valueOf(false);
	public Confirmed confirm = new Confirmed();
	public Folder folder = new Folder();

	public Backpack() {
	}

	public class Confirmed {
		public int ConfirmID;
		public LocalDateTime ConfirmDate;
		public String ConfirmBy;
		public Boolean DocumentConfirmed = Boolean.valueOf(false);

		public Confirmed() {
		}
	}

	public class Folder {
		public int FolderID;
		public String Name;

		public Folder() {
		}
	}

}