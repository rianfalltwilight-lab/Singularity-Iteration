// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks unavailable legacy owner names without initializing or inspecting legacy classes. */
public final class LegacyUuAbsenceProbe {
    private LegacyUuAbsenceProbe() { }
    public static Map<String,Object> verify() throws Exception {
        String prefix="com.singularity_iteration.mio_icif.uu.";
        String[] owners={"ILateRecipeResolver","IRecipeResolver","LeanItemStack","MachineRecipeResolver",
            "RecipeTransformation","SmeltingRecipeResolver","UuGraph","UuGraph$InitialValue","UuGraph$Node",
            "UuGraph$NodeTransform","UuGraph$ValueIterator","UuIndex","UuRecipeWhitelist","UuScanValues","VanillaRecipeResolver"};
        var loader=dev.scex.si.processing.UuPricingLifecycle.class.getClassLoader();
        var rows=new ArrayList<Map<String,Object>>();
        for(String owner:owners){
            String name=prefix+owner;
            if(loader.getResource(name.replace('.','/')+".class")!=null)throw new AssertionError("Retired resource present: "+name);
            try {Class.forName(name,false,loader);throw new AssertionError("Retired owner loadable: "+name);}
            catch(ClassNotFoundException expected){rows.add(Map.of("name",name,"class_unavailable",true,"resource_absent",true));}
        }
        var result=Map.<String,Object>of("passed",true,"legacy_classes",owners.length,"checks",owners.length*2,
            "rows",List.copyOf(rows),"scope","Actual product loader, names only; no legacy implementation inspection or full source clearance.");
        Files.writeString(Path.of("legacy-uu-absence-result.json"),new Gson().toJson(result));
        return result;
    }
}
